package com.passportreader.chip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.gemalto.jp2.JP2Decoder
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformationVerifier
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.text.SimpleDateFormat
import java.util.Locale

enum class AccessControl { PACE, BAC, NONE }
enum class ChipAuthStatus { PASSED, FAILED, NOT_SUPPORTED }

data class PassiveAuthResult(
    /** Data group number to whether its hash matched the signed SOD. */
    val hashes: Map<Int, Boolean>,
    /** Data groups listed in the SOD that we did not read (e.g. DG3 fingerprints need EAC). */
    val notRead: List<Int>,
    val signatureValid: Boolean?,
    val signer: String?,
    val signerValidity: String?,
    val error: String?,
) {
    val allHashesMatch: Boolean get() = hashes.isNotEmpty() && hashes.values.all { it }
}

data class ChipData(
    val mrz: MRZInfo,
    val face: Bitmap?,
    val faceFormat: String?,
    val dg11: DG11File?,
    val dg12: DG12File?,
    val accessControl: AccessControl,
    val passiveAuth: PassiveAuthResult,
    val chipAuth: ChipAuthStatus,
    val chipAuthDetail: String?,
    val ldsVersion: String?,
)

/** Thrown when the chip refuses the MRZ-derived key: the MRZ values are almost certainly wrong. */
class AccessDeniedException(cause: Throwable) : Exception("The chip refused access", cause)

/**
 * Reads an ICAO 9303 ePassport chip with JMRTD.
 *
 * Order matters: access control (PACE, falling back to BAC), then the data groups, then the
 * security checks. Chip Authentication is done last because it swaps the secure-messaging keys -
 * if a chip fumbles it, everything we needed has already been read.
 */
class ChipReader(private val progress: (String) -> Unit) {

    private companion object {
        const val TAG = "ChipReader"
    }

    fun read(tag: Tag, documentNumber: String, birthDate: String, expiryDate: String): ChipData {
        val isoDep = IsoDep.get(tag) ?: throw IllegalStateException("This NFC tag is not a passport chip (no IsoDep).")
        isoDep.timeout = 10_000
        val cardService = CardService.getInstance(isoDep)
        cardService.open()
        try {
            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            service.open()
            val key = BACKey(documentNumber, birthDate, expiryDate)

            // ---- 1. Access control: PACE (newer) with BAC fallback.
            progress("Connecting to chip…")
            var access = AccessControl.NONE
            try {
                // PACE parameters live in EF.CardAccess, which is readable before authentication.
                val cardAccess = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
                val paceInfo = cardAccess.securityInfos.filterIsInstance<PACEInfo>().firstOrNull()
                if (paceInfo != null) {
                    progress("Authenticating (PACE)…")
                    service.doPACE(key, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), null)
                    access = AccessControl.PACE
                }
            } catch (e: Exception) {
                Log.i(TAG, "PACE not available or failed, falling back to BAC: $e")
            }
            service.sendSelectApplet(access == AccessControl.PACE)
            if (access != AccessControl.PACE) {
                // A handful of very old passports have no access control at all.
                val open = runCatching { service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.read() } }.isSuccess
                if (!open) {
                    progress("Authenticating (BAC)…")
                    try {
                        service.doBAC(key)
                        access = AccessControl.BAC
                    } catch (e: CardServiceException) {
                        throw AccessDeniedException(e)
                    }
                }
            }

            // ---- 2. Data groups. Raw bytes are kept for hashing against the SOD.
            val raw = LinkedHashMap<Int, ByteArray>()
            progress("Reading security data (SOD)…")
            val sodBytes = readFile(service, PassportService.EF_SOD)
            val sod = SODFile(ByteArrayInputStream(sodBytes))
            val present = sod.dataGroupHashes.keys

            progress("Reading personal data (DG1)…")
            raw[1] = readFile(service, PassportService.EF_DG1)
            val mrzInfo = DG1File(ByteArrayInputStream(raw[1])).mrzInfo

            var face: Bitmap? = null
            var faceFormat: String? = null
            if (2 in present) {
                progress("Reading photo (DG2)… this is the slow part, keep still")
                runCatching { readFile(service, PassportService.EF_DG2) }
                    .onSuccess { bytes ->
                        raw[2] = bytes
                        val decoded = decodeFace(bytes)
                        face = decoded?.first; faceFormat = decoded?.second
                    }
                    .onFailure { Log.w(TAG, "DG2 read failed", it) }
            }
            val dg11 = if (11 in present) {
                progress("Reading additional personal details (DG11)…")
                runCatching { readFile(service, PassportService.EF_DG11).also { raw[11] = it } }
                    .mapCatching { DG11File(ByteArrayInputStream(it)) }.getOrNull()
            } else null
            val dg12 = if (12 in present) {
                progress("Reading document details (DG12)…")
                runCatching { readFile(service, PassportService.EF_DG12).also { raw[12] = it } }
                    .mapCatching { DG12File(ByteArrayInputStream(it)) }.getOrNull()
            } else null
            val dg14 = if (14 in present) {
                runCatching { readFile(service, PassportService.EF_DG14).also { raw[14] = it } }
                    .mapCatching { DG14File(ByteArrayInputStream(it)) }.getOrNull()
            } else null

            // ---- 3. Passive Authentication: data groups vs. signed hashes, and the signature itself.
            progress("Verifying data integrity…")
            val passive = passiveAuth(sod, sodBytes, raw)

            // ---- 4. Chip Authentication: proves the chip holds the private key for DG14 (anti-cloning).
            val (chipAuth, chipAuthDetail) = if (dg14 == null) {
                ChipAuthStatus.NOT_SUPPORTED to null
            } else {
                progress("Checking chip is genuine…")
                chipAuthenticate(service, dg14)
            }

            return ChipData(
                mrz = mrzInfo,
                face = face,
                faceFormat = faceFormat,
                dg11 = dg11,
                dg12 = dg12,
                accessControl = access,
                passiveAuth = passive,
                chipAuth = chipAuth,
                chipAuthDetail = chipAuthDetail,
                ldsVersion = runCatching { sod.ldsVersion }.getOrNull(),
            )
        } finally {
            runCatching { cardService.close() }
        }
    }

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readFully() }

    private fun InputStream.readFully(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ photo

    /** Returns the decoded face and its format. Handles JPEG and JPEG2000, ISO 19794-5 and 39794. */
    private fun decodeFace(dg2: ByteArray): Pair<Bitmap, String>? {
        // Preferred: JMRTD's structured parse (ISO 19794-5 in today's passports, ISO 39794-5 in LDS 2 ones).
        val imageBytes = runCatching {
            DG2File(ByteArrayInputStream(dg2)).subRecords.firstNotNullOfOrNull { record ->
                when (record) {
                    is FaceInfo -> record.faceImageInfos.firstOrNull()?.imageInputStream
                    is FaceImageDataBlock -> record.representationBlocks.firstOrNull()
                        ?.imageRepresentation2DBlock?.representationData2DInputStream
                    else -> null
                }?.use { it.readFully() }
            }
        }.getOrNull()
        // Fallback: locate the image by its magic bytes (covers encodings JMRTD can't parse).
        val bytes = imageBytes ?: findEmbeddedImage(dg2) ?: return null
        return decodeImage(bytes)
    }

    private fun decodeImage(bytes: ByteArray): Pair<Bitmap, String>? {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it to "JPEG" }
        return try {
            JP2Decoder(bytes).decode()?.let { it to "JPEG 2000" }
        } catch (t: Throwable) { // includes UnsatisfiedLinkError on unsupported CPUs
            Log.w(TAG, "JPEG2000 decode failed", t)
            null
        }
    }

    private fun findEmbeddedImage(data: ByteArray): ByteArray? {
        val signatures = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),                         // JPEG
            byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20),                       // JP2 box
            byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51),                              // J2K codestream
        )
        for (sig in signatures) {
            val idx = indexOf(data, sig)
            if (idx >= 0) return data.copyOfRange(idx, data.size)
        }
        return null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ passive authentication

    private fun passiveAuth(sod: SODFile, sodBytes: ByteArray, raw: Map<Int, ByteArray>): PassiveAuthResult {
        return try {
            val algorithm = normalizeDigest(sod.digestAlgorithm)
            val expected = sod.dataGroupHashes
            val hashes = sortedMapOf<Int, Boolean>()
            for ((dg, bytes) in raw) {
                val signedHash = expected[dg] ?: continue
                val actual = MessageDigest.getInstance(algorithm).digest(bytes)
                hashes[dg] = actual.contentEquals(signedHash)
            }
            val notRead = expected.keys.filter { it !in raw.keys }.sorted()

            val cert: X509Certificate? = runCatching { sod.docSigningCertificate }.getOrNull()
            var signatureValid: Boolean? = null
            var signer: String? = null
            var validity: String? = null
            if (cert != null) {
                signer = cert.subjectX500Principal.name
                    .split(',')
                    .filter { it.trim().startsWith("C=") || it.trim().startsWith("O=") || it.trim().startsWith("CN=") }
                    .joinToString(", ") { it.trim() }
                val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
                validity = "${fmt.format(cert.notBefore)} – ${fmt.format(cert.notAfter)}"
                signatureValid = runCatching { verifySodSignature(sodBytes, cert) }
                    .onFailure { Log.w(TAG, "SOD signature check failed", it) }
                    .getOrDefault(false)
            }
            PassiveAuthResult(hashes, notRead, signatureValid, signer, validity, null)
        } catch (e: Exception) {
            Log.w(TAG, "Passive authentication error", e)
            PassiveAuthResult(emptyMap(), emptyList(), null, null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The SOD is a CMS SignedData wrapped in an ICAO application tag (0x77); unwrap and verify. */
    private fun verifySodSignature(sodBytes: ByteArray, cert: X509Certificate): Boolean {
        require(sodBytes[0] == 0x77.toByte()) { "Unexpected SOD tag" }
        val lenByte = sodBytes[1].toInt() and 0xFF
        val header = if (lenByte < 0x80) 2 else 2 + (lenByte and 0x7F)
        val cms = CMSSignedData(sodBytes.copyOfRange(header, sodBytes.size))
        val verifier: SignerInformationVerifier = JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert)
        val signers = cms.signerInfos.signers
        return signers.isNotEmpty() && signers.all { it.verify(verifier) }
    }

    private fun normalizeDigest(name: String): String {
        val upper = name.uppercase(Locale.ROOT)
        return when {
            upper.startsWith("SHA") && !upper.startsWith("SHA-") -> "SHA-" + upper.removePrefix("SHA")
            else -> upper
        }
    }

    // ------------------------------------------------------------------ chip authentication

    private fun chipAuthenticate(service: PassportService, dg14: DG14File): Pair<ChipAuthStatus, String?> {
        val keys = dg14.securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>()
        if (keys.isEmpty()) return ChipAuthStatus.NOT_SUPPORTED to null
        val infos = dg14.securityInfos.filterIsInstance<ChipAuthenticationInfo>()
        var lastError: String? = null
        for (keyInfo in keys) {
            val info = infos.firstOrNull { it.keyId == keyInfo.keyId } ?: infos.firstOrNull()
            // Documents without ChipAuthenticationInfo use the 3DES variant implied by the key type.
            val caOid = info?.objectIdentifier ?: if (keyInfo.subjectPublicKey is ECPublicKey) {
                SecurityInfo.ID_CA_ECDH_3DES_CBC_CBC
            } else {
                SecurityInfo.ID_CA_DH_3DES_CBC_CBC
            }
            try {
                service.doEACCA(keyInfo.keyId, caOid, keyInfo.objectIdentifier, keyInfo.subjectPublicKey)
                // New session keys are now in use. A successful read proves the chip derived them,
                // i.e. it really holds the private key whose public half is signed in DG14.
                service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.read() }
                return ChipAuthStatus.PASSED to ChipAuthenticationInfo.toKeyAgreementAlgorithm(caOid)
            } catch (e: Exception) {
                Log.w(TAG, "Chip authentication failed with $caOid", e)
                lastError = e.message ?: e.javaClass.simpleName
                // Secure messaging is now in an unknown state; don't try further keys.
                break
            }
        }
        return ChipAuthStatus.FAILED to lastError
    }
}
