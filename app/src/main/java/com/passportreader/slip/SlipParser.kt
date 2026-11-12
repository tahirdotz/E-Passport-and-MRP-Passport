package com.passportreader.slip

import java.time.LocalDate
import java.util.Locale

/** One recognised line of text and its box, in upright image pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2
}

enum class SlipField(val label: String) {
    APPLICATION_ID("Application ID"),
    NAME("Name"),
    PASSPORT_TYPE("Passport type"),
    DELIVERY_TYPE("Delivery type"),
    COLLECTION_DATE("Tentative collection date"),
    NID("NID"),
    DATE_OF_BIRTH("Date of birth"),
    BARCODE("Barcode"),
}

/**
 * A value read for one field. [confirmed] means a second, independent source agreed
 * (e.g. application ID = end of the barcode), so it can be trusted from a single frame.
 */
data class FieldRead(val value: String, val confirmed: Boolean, val note: String? = null)

data class SlipRead(val fields: Map<SlipField, FieldRead>)

/**
 * Pulls the fields out of a Bangladesh passport delivery slip.
 *
 * Every wanted field has an English label printed next to (or above) it, so the parser finds
 * labels with typo-tolerant matching (OCR turns "Collection" into "Coliection") and then takes the
 * value on the same line, to the right, or just below - whichever the slip layout uses.
 */
object SlipParser {

    private val passportTypes = listOf("ORDINARY", "OFFICIAL", "DIPLOMATIC")
    private val deliveryTypes = listOf("SUPER EXPRESS", "EXPRESS", "REGULAR")
    /** Words that only appear in labels; a line containing one of them and a '/' is a label, not a value. */
    private val labelWords = listOf(
        "type", "country", "code", "surname", "given", "name", "nationality", "personal", "previous",
        "passport", "birth", "date", "sex", "place", "issue", "issuing", "authority", "expiry", "holder", "signature",
    )

    fun parse(lines: List<OcrLine>, barcodes: List<String>, today: LocalDate = LocalDate.now()): SlipRead {
        val out = LinkedHashMap<SlipField, FieldRead>()

        // ---- Barcode: the scanner's decode is exact; OCR of the number printed under it is a fallback.
        val scanned = barcodes.map { it.trim() }.filter { it.length >= 8 && it.all(Char::isDigit) }.maxByOrNull { it.length }
        val passportTypeLabel = findLabel(lines, "passport", "type")
        val barcodeText = scanned ?: printedBarcodeNumber(lines, passportTypeLabel)

        // ---- Application ID: the big number top-left, which is the tail of the barcode number.
        val appId = applicationId(lines, barcodeText)
        if (appId != null) {
            val match = barcodeText != null && barcodeText.endsWith(appId)
            out[SlipField.APPLICATION_ID] = FieldRead(appId, match)
        }
        if (barcodeText != null) {
            val confirmed = scanned != null || (appId != null && barcodeText.endsWith(appId))
            out[SlipField.BARCODE] = FieldRead(barcodeText, confirmed, if (scanned == null) "read from printed number" else null)
        }

        // ---- Passport type.
        passportTypeLabel?.let { label ->
            val raw = (afterLabel(label.text, "type") ?: valueRight(lines, label)?.text)?.let(::lettersOnly)
            if (!raw.isNullOrBlank()) {
                val known = passportTypes.firstOrNull { levenshtein(it, raw) <= 2 }
                out[SlipField.PASSPORT_TYPE] = FieldRead(known ?: raw, known != null)
            }
        }

        // ---- Delivery type: Regular / Express / Super Express.
        findLabel(lines, "delivery", "type")?.let { label ->
            val raw = (afterLabel(label.text, "type") ?: valueRight(lines, label)?.text)?.let(::lettersOnly)
            if (!raw.isNullOrBlank()) {
                // Compare without spaces: OCR often drops the one in "SUPER EXPRESS".
                val known = deliveryTypes.firstOrNull { levenshtein(it.replace(" ", ""), raw) <= 2 }
                out[SlipField.DELIVERY_TYPE] = FieldRead(known ?: raw, known != null)
            }
        }

        // ---- Dates. Enrolment date only helps to sanity-check / complete the collection date.
        val enrolLabel = lines.firstOrNull { hasWord(it.text, "enrolment") || hasWord(it.text, "enrollment") }
        val enrolment = enrolLabel?.let { findDate(it.text) ?: valueRight(lines, it)?.let { v -> findDate(v.text) } }

        findLabel(lines, "collection", "date")?.let { label ->
            val sameLine = afterLabel(label.text, "date")?.let(::findDate)
            val right = valueRight(lines, label)
            val date = sameLine ?: right?.let { findDate(it.text) }
            if (date != null) {
                out[SlipField.COLLECTION_DATE] = FieldRead(format(date), true)
            } else {
                // Pen marks often cover the year: keep day/month and take the year from enrolment.
                val partial = partialDate(afterLabel(label.text, "date").orEmpty() + " " + right?.text.orEmpty())
                if (partial != null && enrolment != null) {
                    var guess = runCatching { LocalDate.of(enrolment.year, partial.second, partial.first) }.getOrNull()
                    if (guess != null && guess.isBefore(enrolment)) guess = guess.plusYears(1)
                    if (guess != null) out[SlipField.COLLECTION_DATE] = FieldRead(format(guess), false, "year guessed from enrolment date")
                }
            }
        }

        val dobLabel = lines.firstOrNull { hasWord(it.text, "birth") && hasWord(it.text, "date") && !hasWord(it.text, "place") }
        val dob = dobLabel?.let { label ->
            afterLabel(label.text, "birth")?.let(::findDate) ?: valueBelow(lines, label)?.let { findDate(it.text) }
        }?.takeIf { it.year in 1900..today.year && !it.isAfter(today) }
        if (dob != null) out[SlipField.DATE_OF_BIRTH] = FieldRead(format(dob), true)

        // ---- NID ("Personal No"): 10 (smart card), 13 or 17 digits. Old 17-digit NIDs start with the birth year.
        findLabel(lines, "personal", "no")?.let { label ->
            val candidates = listOfNotNull(afterLabel(label.text, "no"), valueBelow(lines, label)?.text, valueRight(lines, label)?.text)
            val nid = candidates.map(::digitsOf).firstOrNull { it.length == 10 || it.length == 13 || it.length == 17 }
            if (nid != null) {
                val yearOk = nid.length != 17 || dob == null || nid.startsWith(dob.year.toString())
                out[SlipField.NID] = FieldRead(nid, yearOk, if (!yearOk) "doesn't start with birth year" else null)
            }
        }

        // ---- Name: the bearer's full name on the right, cross-checked with Given Name + Surname on the left.
        val bearerName = bearerName(lines)
        val leftName = listOfNotNull(
            findLabel(lines, "given", "name")?.let { valueBelow(lines, it) }?.text?.let(::cleanName),
            findLabel(lines, "surname")?.let { valueBelow(lines, it) }?.text?.let(::cleanName),
        ).filter { it.isNotBlank() }.joinToString(" ")
        when {
            bearerName != null -> out[SlipField.NAME] = FieldRead(bearerName, compact(bearerName) == compact(leftName))
            leftName.isNotBlank() -> out[SlipField.NAME] = FieldRead(leftName, false)
        }
        return SlipRead(out)
    }

    // ------------------------------------------------------------------ specific fields

    private fun printedBarcodeNumber(lines: List<OcrLine>, passportType: OcrLine?): String? {
        val numbers = lines.filter { l -> val d = digitsOf(l.text); d.length in 12..18 && isMostlyDigits(l.text) }
        if (numbers.isEmpty()) return null
        // It sits directly above "Passport Type" in the top-right corner.
        val pick = if (passportType != null) {
            numbers.filter { it.bottom <= passportType.top + passportType.height && it.right > passportType.left - passportType.height * 4 }
                .minByOrNull { passportType.top - it.bottom }
        } else null
        return digitsOf((pick ?: numbers.minBy { it.top }).text)
    }

    private fun applicationId(lines: List<OcrLine>, barcode: String?): String? {
        // e-passport slips may print an explicit "Application ID" label.
        findLabel(lines, "application", "id")?.let { label ->
            val v = (afterLabel(label.text, "id") ?: valueRight(lines, label)?.text)?.let(::digitsOf)
            if (v != null && v.length >= 5) return v
        }
        val candidates = lines.filter { isMostlyDigits(it.text) && digitsOf(it.text).length in 5..8 && it.text.trim().length <= 10 }
        if (candidates.isEmpty()) return null
        if (barcode != null) candidates.firstOrNull { barcode.endsWith(digitsOf(it.text)) }?.let { return digitsOf(it.text) }
        // Otherwise: the large number just above the "Enrolment Date" line, top-left.
        val enrol = lines.firstOrNull { hasWord(it.text, "enrolment") || hasWord(it.text, "enrollment") }
        val pick = if (enrol != null) {
            candidates.filter { it.bottom <= enrol.top + enrol.height }.minByOrNull { (enrol.top - it.bottom) + kotlin.math.abs(enrol.left - it.left) }
        } else null
        return digitsOf((pick ?: candidates.maxBy { it.height }).text)
    }

    private fun bearerName(lines: List<OcrLine>): String? {
        val header = lines.firstOrNull { hasWord(it.text, "bearer") }
        // A label that is just "Name" - not "Father's Name", "RPO Name", "Bank Name", "Given Name"...
        val labels = lines.filter { l ->
            val tokens = tokens(l.text)
            tokens.firstOrNull() == "name" && (header == null || l.top >= header.top)
        }.sortedBy { it.top }
        for (label in labels) {
            val sameLine = label.text.substringAfter(':', "").let(::cleanName)
            val value = if (sameLine.length >= 2) sameLine else valueRight(lines, label)?.text?.let(::cleanName)
            if (!value.isNullOrBlank() && value.count { it.isLetter() } >= 2) return value
        }
        return null
    }

    // ------------------------------------------------------------------ layout helpers

    /** First line containing all [words] (typo-tolerant). */
    internal fun findLabel(lines: List<OcrLine>, vararg words: String): OcrLine? =
        lines.firstOrNull { l -> words.all { hasWord(l.text, it) } }

    /** Text after the label word (e.g. "Passport Type: ORDINARY" -> "ORDINARY"). */
    private fun afterLabel(text: String, lastWord: String): String? {
        val colon = text.indexOf(':')
        if (colon >= 0 && colon < text.length - 1) return text.substring(colon + 1).trim().ifBlank { null }
        val m = Regex("[A-Za-z]+").findAll(text).lastOrNull { isClose(it.value.lowercase(Locale.ROOT), lastWord) } ?: return null
        return text.substring(m.range.last + 1).trim(' ', ':', '.', '-').ifBlank { null }
    }

    /** Nearest non-label line on the same row, to the right of [label]. */
    private fun valueRight(lines: List<OcrLine>, label: OcrLine): OcrLine? = lines
        .filter { it !== label && kotlin.math.abs(it.centerY - label.centerY) <= label.height * 0.7 && it.left >= label.right - label.height }
        .filter { !isLabel(it) }
        .minByOrNull { it.left }

    /** Nearest non-label line directly under [label] (same column). */
    private fun valueBelow(lines: List<OcrLine>, label: OcrLine): OcrLine? = lines
        .filter { it !== label && it.top >= label.centerY && it.top - label.bottom <= label.height * 1.5 }
        .filter { kotlin.math.abs(it.left - label.left) <= label.height * 3 }
        .filter { !isLabel(it) }
        .minByOrNull { it.top }

    private fun isLabel(line: OcrLine): Boolean {
        val t = line.text.trim()
        if (t.endsWith(":")) return true
        return t.contains('/') && tokens(t).any { tok -> labelWords.any { isClose(tok, it) } }
    }

    // ------------------------------------------------------------------ text helpers

    private fun tokens(text: String): List<String> =
        Regex("[A-Za-z]+").findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()

    internal fun hasWord(text: String, word: String): Boolean = tokens(text).any { isClose(it, word) }

    private fun isClose(token: String, word: String): Boolean {
        val allowed = when {
            word.length <= 3 -> 0
            word.length <= 5 -> 1
            else -> 2
        }
        if (kotlin.math.abs(token.length - word.length) > allowed) return false
        return levenshtein(token, word) <= allowed
    }

    internal fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private val digitLookalikes = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2', 'G' to '6',
    )

    /** Digits in a numeric value, fixing letters OCR commonly puts in place of digits. */
    internal fun digitsOf(text: String): String = text.map { digitLookalikes[it] ?: it }.filter { it.isDigit() }.joinToString("")

    private fun isMostlyDigits(text: String): Boolean {
        val t = text.filter { !it.isWhitespace() }
        if (t.isEmpty()) return false
        val digits = t.count { it.isDigit() }
        return digits * 10 >= t.length * 8
    }

    /** First dd/mm/yyyy date, after fixing digit lookalikes inside date-like tokens. */
    internal fun findDate(text: String): LocalDate? {
        val fixed = text.split(Regex("\\s+")).joinToString(" ") { tok ->
            if (tok.contains('/') || tok.count { it.isDigit() } * 2 >= tok.length) tok.map { digitLookalikes[it] ?: it }.joinToString("") else tok
        }
        for (m in Regex("(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\s*[/.\\-]\\s*(\\d{4})").findAll(fixed)) {
            val (d, mo, y) = m.destructured
            val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
            if (date != null && date.year in 1900..2100) return date
        }
        return null
    }

    /** Day and month from a date whose year is unreadable, e.g. "28/09/20šșš". */
    private fun partialDate(text: String): Pair<Int, Int>? {
        val fixed = text.map { digitLookalikes[it] ?: it }.joinToString("")
        val m = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/").find(fixed) ?: return null
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        return if (d in 1..31 && mo in 1..12) d to mo else null
    }

    private fun format(d: LocalDate) = "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)

    private fun lettersOnly(s: String) = s.uppercase(Locale.ROOT).filter { it.isLetter() }

    internal fun cleanName(s: String): String = s.uppercase(Locale.ROOT)
        .map { if (it.isLetter() || it == '-' || it == '.' || it == '\'') it else ' ' }
        .joinToString("")
        .trim(' ', '-', '.')
        .replace(Regex("\\s+"), " ")

    private fun compact(s: String) = s.uppercase(Locale.ROOT).filter { it.isLetter() }
}

/**
 * Merges reads across camera frames. Confirmed values are taken at once; unconfirmed ones once
 * the same value has been read [votesNeeded] times (3 for an OCR'd barcode number).
 */
class SlipAccumulator(private val votesNeeded: Int = 2) {
    private val votes = HashMap<SlipField, HashMap<String, Int>>()
    private val accepted = LinkedHashMap<SlipField, FieldRead>()

    val fields: Map<SlipField, FieldRead> get() = accepted
    val isComplete: Boolean get() = accepted.size == SlipField.entries.size

    fun add(read: SlipRead) {
        // A confirmed barcode that differs from what we have means a different slip is in view.
        val newBarcode = read.fields[SlipField.BARCODE]
        val oldBarcode = accepted[SlipField.BARCODE]
        if (newBarcode != null && newBarcode.confirmed && oldBarcode != null && oldBarcode.value != newBarcode.value) reset()

        for ((field, value) in read.fields) {
            val current = accepted[field]
            if (current != null && current.confirmed) continue
            if (value.confirmed) { accepted[field] = value; continue }
            val v = votes.getOrPut(field) { HashMap() }
            val n = (v[value.value] ?: 0) + 1
            v[value.value] = n
            val needed = if (field == SlipField.BARCODE) votesNeeded + 1 else votesNeeded
            if (n >= needed) accepted[field] = value
        }
    }

    /** Best guess for every field seen so far (accepted first, else most-voted) - for "save anyway". */
    fun bestEffort(): Map<SlipField, FieldRead> {
        val out = LinkedHashMap<SlipField, FieldRead>()
        for (f in SlipField.entries) {
            val a = accepted[f]
            if (a != null) { out[f] = a; continue }
            votes[f]?.maxByOrNull { it.value }?.let { out[f] = FieldRead(it.key, false) }
        }
        return out
    }

    fun reset() { votes.clear(); accepted.clear() }
}
