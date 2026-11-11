package com.passportreader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.passportreader.MrzSource
import com.passportreader.R
import com.passportreader.Session
import com.passportreader.chip.AccessControl
import com.passportreader.chip.ChipActivity
import com.passportreader.chip.ChipAuthStatus
import com.passportreader.chip.ChipData
import com.passportreader.mrz.MrzData
import com.passportreader.mrz.MrzDates
import net.sf.scuba.data.Gender
import java.time.LocalDate

/** Shows everything read from the passport: MRZ only (MRP) or MRZ + chip (ePassport). */
class ResultActivity : ComponentActivity() {

    private lateinit var content: LinearLayout
    private var photoView: ImageView? = null
    private var photoCaption: TextView? = null
    private val copyText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        findViewById<View>(R.id.root).applySystemBarPadding()
        content = findViewById(R.id.content)

        val mrz = Session.mrz
        if (mrz == null) { finish(); return }
        render(mrz, Session.chip)
    }

    private fun render(scanned: MrzData, chip: ChipData?) {
        content.removeAllViews()
        copyText.clear()
        title(getString(R.string.result_title))
        banner(chip)
        photoRow(chip)

        // Prefer the chip's copy of the MRZ: it is signed, the scan is OCR.
        val m = chip?.let { fromChip(it) } ?: scanned

        section("Personal details")
        row("Surname", m.surname)
        row("Given names", m.givenNames)
        chip?.dg11?.nameOfHolder?.let { row("Full name (chip)", clean(it)) }
        chip?.dg11?.otherNames?.takeIf { it.isNotEmpty() }?.let { row("Other names", it.joinToString(", ") { n -> clean(n) }) }
        row("Nationality", Countries.name(m.nationality))
        row("Date of birth", chip?.dg11?.fullDateOfBirth?.let(::formatFullDate) ?: MrzDates.format(m.birthDate, true))
        row("Sex", when (m.sex) { "M" -> "Male"; "F" -> "Female"; "X", "<" -> "Unspecified"; else -> m.sex })
        chip?.dg11?.placeOfBirth?.takeIf { it.isNotEmpty() }?.let { row("Place of birth", it.joinToString(", ") { p -> clean(p) }) }
        row("Personal number", chip?.dg11?.personalNumber?.let(::clean)?.takeIf { it.isNotBlank() } ?: m.personalNumber)
        chip?.dg11?.permanentAddress?.takeIf { it.isNotEmpty() }?.let { row("Address", it.joinToString(", ") { a -> clean(a) }) }
        chip?.dg11?.telephone?.let { row("Telephone", clean(it)) }
        chip?.dg11?.profession?.let { row("Profession", clean(it)) }
        chip?.dg11?.title?.let { row("Title", clean(it)) }
        chip?.dg11?.personalSummary?.let { row("Personal summary", clean(it)) }
        chip?.dg11?.custodyInformation?.let { row("Custody information", clean(it)) }
        chip?.dg11?.otherValidTDNumbers?.takeIf { it.isNotEmpty() }?.let { row("Other valid documents", it.joinToString(", ")) }

        section("Document")
        row("Type", documentType(m.documentCode))
        row("Document number", m.documentNumber)
        row("Issuing state", Countries.name(m.issuingState))
        chip?.dg12?.dateOfIssue?.let { row("Date of issue", formatFullDate(it)) }
        val expiry = MrzDates.parse(m.expiryDate, false)
        val expired = expiry != null && expiry.isBefore(LocalDate.now())
        row(
            "Date of expiry",
            MrzDates.format(m.expiryDate, false) + if (expired) "  (EXPIRED)" else "",
            if (expired) R.color.bad else null,
        )
        chip?.dg12?.issuingAuthority?.let { row("Issuing authority", clean(it)) }
        chip?.dg12?.endorsementsAndObservations?.let { row("Endorsements / observations", clean(it)) }
        chip?.dg12?.taxOrExitRequirements?.let { row("Tax / exit requirements", clean(it)) }
        chip?.dg12?.namesOfOtherPersons?.takeIf { it.isNotEmpty() }?.let { row("Other persons on document", it.joinToString(", ") { n -> clean(n) }) }
        chip?.dg12?.dateAndTimeOfPersonalization?.let { row("Personalised", it) }

        if (chip != null) securitySection(scanned, chip)
        else mrpSection(scanned)

        section("Machine Readable Zone")
        mono(chip?.mrz?.toString()?.trim() ?: scanned.rawMrz)

        Session.pageImage?.let { page ->
            section("Data page (camera)")
            image(page)
        }

        buttons(chip)
        if (chip?.face == null) detectFaceOnPage()
    }

    // ------------------------------------------------------------------ sections

    private fun banner(chip: ChipData?) {
        val (bg, fg, heading, detail) = if (chip != null) {
            val pa = chip.passiveAuth
            val tampered = pa.hashes.values.any { !it } || pa.signatureValid == false
            val verified = pa.allHashesMatch && pa.signatureValid == true && chip.chipAuth != ChipAuthStatus.FAILED
            when {
                tampered -> Banner(R.drawable.card_bad, R.color.bad, "e-passport: verification FAILED",
                    "Chip data does not match its digital signature. Treat this document as suspicious.")
                chip.chipAuth == ChipAuthStatus.FAILED -> Banner(R.drawable.card_warn, R.color.warn, "e-passport: chip read",
                    "Data signature is valid, but the chip-cloning check did not pass.")
                verified -> Banner(R.drawable.card_ok, R.color.ok, "e-passport: chip read and verified",
                    "Data on the chip is intact and signed by the issuing country's document signer.")
                else -> Banner(R.drawable.card_warn, R.color.warn, "e-passport: chip read",
                    "Some security checks could not be completed. See Security below.")
            }
        } else {
            Banner(R.drawable.card_neutral, R.color.text, "MRP: read from the printed MRZ",
                "No chip was read. All MRZ check digits are valid. Compare the photo and printed details by eye.")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = heading; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(fg))
        })
        box.addView(TextView(this).apply {
            text = detail; textSize = 14f; setTextColor(color(R.color.text)); setPadding(0, dp(4), 0, 0)
        })
        content.addView(box, lp(top = 16))
        copyText.append(heading).append('\n')
    }

    private data class Banner(val bg: Int, val fg: Int, val heading: String, val detail: String)

    private fun photoRow(chip: ChipData?) {
        val face = chip?.face ?: Session.pageFace
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.card)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
            contentDescription = "Holder photo"
            face?.let(::setImageBitmap)
        }
        val caption = TextView(this).apply {
            textSize = 13f; setTextColor(color(R.color.text_secondary)); gravity = Gravity.CENTER
            text = when {
                chip?.face != null -> "Photo from chip (${chip.faceFormat})"
                face != null -> "Photo cut from page scan"
                Session.pageImage != null -> "Looking for the photo…"
                else -> "No photo"
            }
        }
        photoView = iv; photoCaption = caption
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        wrap.addView(iv, LinearLayout.LayoutParams(dp(150), dp(190)))
        wrap.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        content.addView(wrap, lp(top = 20))
    }

    private fun securitySection(scanned: MrzData, chip: ChipData) {
        section("Security (chip)")
        val pa = chip.passiveAuth
        row("Chip access", when (chip.accessControl) {
            AccessControl.PACE -> "PACE (modern)"
            AccessControl.BAC -> "BAC (basic access control)"
            AccessControl.NONE -> "None (unprotected chip)"
        })
        if (pa.error != null) {
            row("Data integrity", "Could not verify: ${pa.error}", R.color.warn)
        } else {
            val hashes = pa.hashes.entries.joinToString("  ") { (dg, ok) -> "DG$dg ${if (ok) "✓" else "✗"}" }
            row("Data group hashes", hashes.ifEmpty { "—" }, if (pa.allHashesMatch) R.color.ok else R.color.bad)
            if (pa.notRead.isNotEmpty()) {
                row("Not read", pa.notRead.joinToString(", ") { dgName(it) })
            }
            row("Document signature", when (pa.signatureValid) {
                true -> "Valid ✓"; false -> "INVALID ✗"; null -> "No signer certificate on chip"
            }, when (pa.signatureValid) { true -> R.color.ok; false -> R.color.bad; null -> R.color.warn })
            pa.signer?.let { row("Signed by", it) }
            pa.signerValidity?.let { row("Signer certificate valid", it) }
        }
        row("Anti-cloning (Chip Authentication)", when (chip.chipAuth) {
            ChipAuthStatus.PASSED -> "Passed ✓" + (chip.chipAuthDetail?.let { " ($it)" } ?: "")
            ChipAuthStatus.NOT_SUPPORTED -> "Not supported by this passport"
            ChipAuthStatus.FAILED -> "Failed" + (chip.chipAuthDetail?.let { ": $it" } ?: "")
        }, when (chip.chipAuth) { ChipAuthStatus.PASSED -> R.color.ok; ChipAuthStatus.FAILED -> R.color.bad; else -> null })

        if (Session.mrzSource == MrzSource.CAMERA && scanned.line2.isNotEmpty()) {
            val chipMrz = fromChip(chip)
            val diffs = buildList {
                if (scanned.documentNumber != chipMrz.documentNumber) add("document number")
                if (scanned.birthDate != chipMrz.birthDate) add("date of birth")
                if (scanned.expiryDate != chipMrz.expiryDate) add("expiry date")
                if (scanned.nationality != chipMrz.nationality) add("nationality")
                if (scanned.sex != chipMrz.sex) add("sex")
                if (scanned.line1.isNotEmpty() && norm(scanned.surname + scanned.givenNames) != norm(chipMrz.surname + chipMrz.givenNames)) add("name")
            }
            row("Printed page vs chip", if (diffs.isEmpty()) "Match ✓" else "Differs: ${diffs.joinToString()}",
                if (diffs.isEmpty()) R.color.ok else R.color.warn)
        }
        chip.ldsVersion?.let { row("LDS version", it) }
        note("Not checked: whether the signer's certificate chains to the issuing country's root (CSCA). That needs the ICAO or national master list.")
    }

    private fun mrpSection(scanned: MrzData) {
        section("Checks")
        row("MRZ check digits", "All valid ✓", R.color.ok)
        row("Source", if (Session.mrzSource == MrzSource.CAMERA) "Camera OCR, confirmed on 2 frames" else "Typed in")
        note("A passport without a chip can't be verified electronically. Check the photo, printed details and physical security features by eye.")
    }

    private fun buttons(chip: ChipData?) {
        val nfcOn = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
        if (chip == null && nfcOn) {
            button(getString(R.string.read_chip), primary = true) {
                startActivity(Intent(this, ChipActivity::class.java)); finish()
            }
        }
        button(getString(R.string.copy), primary = false) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Passport", copyText.toString()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        button(getString(R.string.scan_another), primary = chip != null || !nfcOn) { finish() }
    }

    // ------------------------------------------------------------------ face crop for MRPs

    private fun detectFaceOnPage() {
        val page = Session.pageImage ?: return
        if (Session.pageFace != null) return
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
        )
        detector.process(InputImage.fromBitmap(page, 0))
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    photoCaption?.text = "No photo found on page scan"
                    return@addOnSuccessListener
                }
                val crop = cropPortrait(page, face.boundingBox)
                Session.pageFace = crop
                photoView?.setImageBitmap(crop)
                photoCaption?.text = "Photo cut from page scan"
            }
            .addOnFailureListener { photoCaption?.text = "No photo found on page scan" }
            .addOnCompleteListener { detector.close() }
    }

    /** Expands the face box to a passport-photo-like crop (head and shoulders, 3:4). */
    private fun cropPortrait(src: Bitmap, box: Rect): Bitmap {
        val w = (box.width() * 1.8f).toInt()
        val h = (w * 4f / 3f).toInt()
        val cx = box.centerX(); val cy = box.centerY() + box.height() / 8
        val left = (cx - w / 2).coerceIn(0, src.width - 1)
        val top = (cy - h / 2).coerceIn(0, src.height - 1)
        val right = (left + w).coerceAtMost(src.width)
        val bottom = (top + h).coerceAtMost(src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    // ------------------------------------------------------------------ data helpers

    private fun fromChip(chip: ChipData): MrzData {
        val i = chip.mrz
        return MrzData(
            documentCode = i.documentCode.trimEnd('<'),
            issuingState = i.issuingState.trimEnd('<'),
            surname = i.primaryIdentifier.replace('<', ' ').trim(),
            givenNames = i.secondaryIdentifier.replace('<', ' ').trim().replace(Regex(" +"), " "),
            documentNumber = i.documentNumber.trimEnd('<'),
            nationality = i.nationality.trimEnd('<'),
            birthDate = i.dateOfBirth,
            sex = when (i.genderCode) { Gender.MALE -> "M"; Gender.FEMALE -> "F"; else -> "X" },
            expiryDate = i.dateOfExpiry,
            personalNumber = (i.personalNumber ?: "").trimEnd('<'),
            line1 = "", line2 = "",
        )
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetter() }

    private fun clean(s: String) = s.replace("<<", ", ").replace('<', ' ').trim()

    /** yyyyMMdd (DG11/DG12) to a readable date; anything else is returned unchanged. */
    private fun formatFullDate(s: String): String {
        val d = s.filter { it.isDigit() }
        if (d.length != 8) return s
        return runCatching {
            LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH))
        }.getOrDefault(s)
    }

    private fun documentType(code: String) = when {
        code.startsWith("P") -> "Passport" + if (code.length > 1 && code[1] != '<') " ($code)" else ""
        code.startsWith("I") || code.startsWith("A") || code.startsWith("C") -> "Identity card ($code)"
        code.startsWith("V") -> "Visa ($code)"
        code.isEmpty() -> ""
        else -> code
    }

    private fun dgName(dg: Int) = when (dg) {
        3 -> "DG3 fingerprints (government access only)"
        4 -> "DG4 iris (government access only)"
        5 -> "DG5 displayed portrait"
        7 -> "DG7 signature image"
        13 -> "DG13 national data"
        15 -> "DG15 active authentication key"
        16 -> "DG16 emergency contacts"
        else -> "DG$dg"
    }

    // ------------------------------------------------------------------ view helpers

    private fun title(text: String) {
        content.addView(TextView(this).apply {
            this.text = text; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.text))
        }, lp(top = 12))
    }

    private var currentCard: LinearLayout? = null

    private fun section(name: String) {
        content.addView(TextView(this).apply {
            text = name.uppercase(); textSize = 12f; letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.text_secondary))
        }, lp(top = 24))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        content.addView(card, lp(top = 8))
        currentCard = card
        copyText.append("\n== ").append(name).append(" ==\n")
    }

    private fun row(label: String, value: String?, valueColor: Int? = null) {
        if (value.isNullOrBlank()) return
        val card = currentCard ?: return
        card.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(color(R.color.text_secondary))
        }, lp(top = 10))
        card.addView(TextView(this).apply {
            text = value; textSize = 16f; setTextIsSelectable(true)
            setTextColor(color(valueColor ?: R.color.text))
        }, lp(top = 2))
        copyText.append(label).append(": ").append(value).append('\n')
    }

    private fun note(text: String) {
        currentCard?.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(color(R.color.text_secondary))
        }, lp(top = 14))
    }

    private fun mono(text: String) {
        currentCard?.addView(TextView(this).apply {
            this.text = text; typeface = Typeface.MONOSPACE; textSize = 11.5f
            setTextColor(color(R.color.text)); setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }, lp(top = 10))
        copyText.append(text).append('\n')
    }

    private fun image(bmp: Bitmap) {
        currentCard?.addView(ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; contentDescription = "Data page photo"
        }, lp(top = 10))
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) {
        val b = Button(this, null, 0, if (primary) R.style.PrimaryButton else R.style.SecondaryButton).apply {
            this.text = text; setOnClickListener { onClick() }
        }
        content.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
