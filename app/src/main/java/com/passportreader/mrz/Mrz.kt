package com.passportreader.mrz

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Data from a passport's Machine Readable Zone (ICAO 9303 part 4, TD3: two lines of 44 chars).
 * Dates are kept exactly as printed (YYMMDD) because that is what the chip's BAC/PACE key needs.
 */
data class MrzData(
    val documentCode: String,
    val issuingState: String,
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val nationality: String,
    val birthDate: String,
    val sex: String,
    val expiryDate: String,
    val personalNumber: String,
    val line1: String,
    val line2: String,
) {
    val rawMrz: String get() = if (line1.isEmpty()) line2 else "$line1\n$line2"

    companion object {
        /** Just the three values needed to open a chip, typed in by hand. */
        fun forChipAccess(documentNumber: String, birthDate: String, expiryDate: String) = MrzData(
            documentCode = "", issuingState = "", surname = "", givenNames = "",
            documentNumber = documentNumber.uppercase(Locale.ROOT).trim(),
            nationality = "", birthDate = birthDate, sex = "", expiryDate = expiryDate,
            personalNumber = "", line1 = "", line2 = "",
        )
    }
}

object MrzDates {
    private val display = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

    /** YYMMDD to a date. Birth dates in the future roll back a century. */
    fun parse(yymmdd: String, isBirthDate: Boolean, today: LocalDate = LocalDate.now()): LocalDate? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        val year = if (isBirthDate) {
            if (2000 + yy > today.year) 1900 + yy else 2000 + yy
        } else {
            if (yy >= 70) 1900 + yy else 2000 + yy
        }
        return runCatching { LocalDate.of(year, mm, dd) }.getOrNull()
    }

    fun format(yymmdd: String, isBirthDate: Boolean): String =
        parse(yymmdd, isBirthDate)?.format(display) ?: yymmdd

    fun isValid(yymmdd: String): Boolean {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return false
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        return mm in 1..12 && dd in 1..31
    }
}

object MrzChecksum {
    private val weights = intArrayOf(7, 3, 1)

    fun value(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        '<' -> 0
        else -> -1
    }

    fun compute(field: String): Int {
        var sum = 0
        field.forEachIndexed { i, c ->
            val v = value(c)
            if (v < 0) return -1
            sum += v * weights[i % 3]
        }
        return sum % 10
    }

    /** A check digit printed as '<' counts as 0 (used when an optional field is empty). */
    fun matches(field: String, check: Char): Boolean {
        val expected = compute(field)
        if (expected < 0) return false
        return when (check) {
            '<' -> expected == 0
            in '0'..'9' -> check - '0' == expected
            else -> false
        }
    }
}

/**
 * Turns noisy OCR text into a validated TD3 MRZ.
 *
 * OCR on the MRZ typically fails in a few predictable ways, all handled here:
 *  - letters and digits swapped in fields that can only hold one kind (O/0, I/1, S/5, B/8 ...)
 *  - the run of '<' fillers counted wrong, or read as '«', 'K' or 'C'
 *  - spaces inserted inside a line
 * Nothing is accepted unless every check digit, including the composite one, agrees.
 */
object MrzParser {
    const val LINE_LENGTH = 44

    private val toDigit = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0', 'C' to '0',
        'I' to '1', 'L' to '1', 'J' to '1', 'T' to '7',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6', 'A' to '4',
    )
    private val toLetter = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G', '4' to 'A', '7' to 'T',
    )
    /** Characters OCR tends to produce for a '<' filler. */
    private val fillerLookalikes = setOf('<', 'K', 'C', '(', '[', '{')
    /** Letters that OCR produces for digits; swapped back only if a check digit demands it. */
    private val docNumberAlternatives = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6',
    )

    /** Uppercase, strip spaces, map lookalike symbols, drop anything that can't appear in an MRZ. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.uppercase(Locale.ROOT)) {
            when (ch) {
                '«' -> sb.append("<<")
                '‹', '〈', '＜', '(', '[', '{' -> sb.append('<')
                in 'A'..'Z', in '0'..'9', '<' -> sb.append(ch)
                else -> {} // spaces, punctuation, '»' etc.
            }
        }
        return sb.toString()
    }

    /**
     * Searches OCR rows (top to bottom) for a passport MRZ. Returns null unless a fully
     * check-digit-valid line 2 is found.
     */
    fun findInRows(rows: List<String>): MrzData? {
        val normalized = rows.map(::normalize)
        for (i in normalized.indices) {
            val line2 = repairLine2(normalized[i]) ?: continue
            // Line 1 is the row directly above; sometimes OCR splits it, so also try two rows joined.
            val above = buildList {
                if (i >= 1) add(normalized[i - 1])
                if (i >= 2) add(normalized[i - 2] + normalized[i - 1])
            }
            val line1 = above.map(::repairLine1).firstOrNull { it != null }
            return parse(line1 ?: "", line2)
        }
        return null
    }

    /** Parse two already-clean lines. Line 1 may be empty if it wasn't readable. */
    fun parse(line1: String, line2: String): MrzData? {
        if (line2.length != LINE_LENGTH || !isLine2Valid(line2)) return null
        var surname = ""
        var given = ""
        var code = ""
        var issuing = ""
        if (line1.length == LINE_LENGTH) {
            code = line1.substring(0, 2).trimEnd('<')
            issuing = line1.substring(2, 5).trimEnd('<')
            val names = line1.substring(5).trimEnd('<')
            val split = names.indexOf("<<")
            if (split >= 0) {
                surname = names.substring(0, split).replace('<', ' ').trim()
                given = names.substring(split + 2).replace('<', ' ').trim().replace(Regex(" +"), " ")
            } else {
                surname = names.replace('<', ' ').trim()
            }
        }
        return MrzData(
            documentCode = code,
            issuingState = issuing,
            surname = surname,
            givenNames = given,
            documentNumber = line2.substring(0, 9).trimEnd('<'),
            nationality = line2.substring(10, 13).trimEnd('<'),
            birthDate = line2.substring(13, 19),
            sex = line2[20].let { if (it == '<') "X" else it.toString() },
            expiryDate = line2.substring(21, 27),
            personalNumber = line2.substring(28, 42).trimEnd('<'),
            line1 = line1,
            line2 = line2,
        )
    }

    fun isLine2Valid(l: String): Boolean {
        if (l.length != LINE_LENGTH) return false
        if (!MrzChecksum.matches(l.substring(0, 9), l[9])) return false
        if (!MrzChecksum.matches(l.substring(13, 19), l[19])) return false
        if (!MrzChecksum.matches(l.substring(21, 27), l[27])) return false
        if (!MrzChecksum.matches(l.substring(28, 42), l[42])) return false
        val composite = l.substring(0, 10) + l.substring(13, 20) + l.substring(21, 43)
        if (!MrzChecksum.matches(composite, l[43])) return false
        if (!MrzDates.isValid(l.substring(13, 19)) || !MrzDates.isValid(l.substring(21, 27))) return false
        if (l[20] !in "MFX<") return false
        return true
    }

    /**
     * Line 2 layout: docNo(9) cd nat(3) dob(6) cd sex exp(6) cd personal(14) cd composite.
     * Returns a valid 44-char line or null.
     */
    fun repairLine2(input: String): String? {
        if (input.length < 38 || input.length > 52) return null
        // The first 28 characters have a fixed format; the variable part is the personal number
        // field (mostly fillers), so rebuild it to exactly 14 chars.
        val head = input.substring(0, 28)
        val tail = input.substring(input.length - 2)
        val middle = input.substring(28, input.length - 2)
        // First trust only real '<' fillers; if that fails, also treat K/C lookalikes as fillers.
        val strict = middle.trimEnd('<')
        val loose = middle.trimEnd { it in fillerLookalikes }.let { core ->
            if (core.all { it in fillerLookalikes }) "" else core
        }
        for (personalCore in listOf(strict, loose).distinct()) {
            if (personalCore.length > 14) continue
            repairFixedLine2(head + personalCore.padEnd(14, '<') + tail)?.let { return it }
        }
        return null
    }

    private fun repairFixedLine2(line: String): String? {
        val chars = line.toCharArray()
        fun digits(range: IntRange) { for (i in range) chars[i] = toDigit[chars[i]] ?: chars[i] }
        fun letters(range: IntRange) { for (i in range) chars[i] = toLetter[chars[i]] ?: chars[i] }

        digits(9..9)                  // doc number check digit
        letters(10..12)               // nationality
        digits(13..19)                // birth date + check
        chars[20] = when (chars[20]) { 'M', 'F', 'X', '<' -> chars[20]; 'H', 'N' -> 'M'; 'E', 'P' -> 'F'; 'K' -> '<'; else -> chars[20] }
        digits(21..27)                // expiry + check
        // Check digit of an empty personal number may be '<'; otherwise digit.
        if (chars[42] != '<') digits(42..42)
        digits(43..43)

        val base = String(chars)
        if (isLine2Valid(base)) return base

        // Document numbers mix letters and digits, so only the check digit can tell O from 0.
        // Try swapping ambiguous characters (bounded), and accept only a unique valid result.
        val ambiguous = (0 until 9).filter { docNumberAlternatives.containsKey(chars[it]) }
        if (ambiguous.isEmpty() || ambiguous.size > 5) return null
        val valid = mutableSetOf<String>()
        for (mask in 1 until (1 shl ambiguous.size)) {
            val c = chars.copyOf()
            ambiguous.forEachIndexed { bit, idx ->
                if (mask and (1 shl bit) != 0) c[idx] = docNumberAlternatives.getValue(c[idx])
            }
            val candidate = String(c)
            if (isLine2Valid(candidate)) valid += candidate
        }
        return valid.singleOrNull()
    }

    /** Line 1 layout: 'P' type issuingState(3) names(39). Not check-digit protected. */
    fun repairLine1(input: String): String? {
        if (input.length < 30 || input.length > 52) return null
        if (input[0] != 'P') return null
        val chars = input.toCharArray()
        // Document type letter 2, issuing state and names are alphabetic only.
        for (i in 1 until chars.size) chars[i] = toLetter[chars[i]] ?: chars[i]
        var s = String(chars)
        // Trailing fillers misread as K/C: from the first '<' after which only filler-lookalikes follow.
        val tailStart = (5 until s.length).firstOrNull { i ->
            s[i] == '<' && s.substring(i).all { it in fillerLookalikes }
        }
        if (tailStart != null) s = s.substring(0, tailStart)
        s = s.trimEnd('<')
        if (s.length > LINE_LENGTH) return null
        s = s.padEnd(LINE_LENGTH, '<')
        if (!s.substring(2, 5).all { it in 'A'..'Z' || it == '<' }) return null
        if (!s.contains("<<")) return null
        return s
    }
}

/**
 * Collects MRZ reads across camera frames and only accepts one once the same line 2 has been
 * read [required] times. Line 1 (names, no check digits) is taken by majority vote.
 */
class MrzAccumulator(private val required: Int = 2) {
    private val line2Counts = HashMap<String, Int>()
    private val line1Votes = HashMap<String, HashMap<String, Int>>()

    /** Returns the agreed result once enough frames agree, else null. */
    fun add(read: MrzData): MrzData? {
        val count = (line2Counts[read.line2] ?: 0) + 1
        line2Counts[read.line2] = count
        if (read.line1.isNotEmpty()) {
            val votes = line1Votes.getOrPut(read.line2) { HashMap() }
            votes[read.line1] = (votes[read.line1] ?: 0) + 1
        }
        if (count < required) return null
        val bestLine1 = line1Votes[read.line2]?.maxByOrNull { it.value }?.key
        // Names unreadable but line 2 is solid: give up waiting for line 1 after a few more frames.
        if (bestLine1 == null) return if (count >= required + 3) MrzParser.parse("", read.line2) else null
        return MrzParser.parse(bestLine1, read.line2)
    }

    val hasPartial: Boolean get() = line2Counts.isNotEmpty()

    fun reset() {
        line2Counts.clear(); line1Votes.clear()
    }
}
