package com.passportreader.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MrzParserTest {

    // ICAO 9303 part 4 specimen passport.
    private val l1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    /** Build a valid line 2 with correct check digits. */
    private fun line2(doc: String, nat: String, dob: String, sex: Char, exp: String, personal: String = ""): String {
        val d = doc.padEnd(9, '<')
        val p = personal.padEnd(14, '<')
        val dCd = MrzChecksum.compute(d)
        val dobCd = MrzChecksum.compute(dob)
        val expCd = MrzChecksum.compute(exp)
        val pCd = MrzChecksum.compute(p)
        val partial = "$d$dCd$nat$dob$dobCd$sex$exp$expCd$p$pCd"
        val composite = MrzChecksum.compute(partial.substring(0, 10) + partial.substring(13, 20) + partial.substring(21, 43))
        return partial + composite
    }

    @Test fun checkDigitsOfSpecimen() {
        assertTrue(MrzParser.isLine2Valid(l2))
        assertEquals(6, MrzChecksum.compute("L898902C3"))
    }

    @Test fun parsesSpecimen() {
        val m = MrzParser.findInRows(listOf("PASSPORT", l1, l2))
        assertNotNull(m)
        m!!
        assertEquals("P", m.documentCode)
        assertEquals("UTO", m.issuingState)
        assertEquals("ERIKSSON", m.surname)
        assertEquals("ANNA MARIA", m.givenNames)
        assertEquals("L898902C3", m.documentNumber)
        assertEquals("UTO", m.nationality)
        assertEquals("740812", m.birthDate)
        assertEquals("F", m.sex)
        assertEquals("120415", m.expiryDate)
        assertEquals("ZE184226B", m.personalNumber)
    }

    @Test fun repairsTypicalOcrNoise() {
        val noisy1 = "P<UTO ERIKSSON«ANNA<MARIA<<<<<<<<<KK<<<<C<"   // space, «, K/C fillers, short
        val noisy2 = "L898902C36UT07408122F12O4159ZE184226B<<<<10"  // 0 in nationality, O in date, filler missing
        val m = MrzParser.findInRows(listOf(noisy1, noisy2))
        assertNotNull(m)
        assertEquals(l1, m!!.line1)
        assertEquals(l2, m.line2)
        assertEquals("ANNA MARIA", m.givenNames)
    }

    @Test fun rejectsBadCompositeCheckDigit() {
        val bad = l2.substring(0, 43) + "1"
        assertNull(MrzParser.findInRows(listOf(l1, bad)))
    }

    @Test fun recoversZeroReadAsLetterOInDocumentNumber() {
        val real = line2("AB0123456", "PAK", "900115", 'M', "300101")
        assertTrue(MrzParser.isLine2Valid(real))
        val ocr = real.replaceFirst("AB0", "ABO")
        assertEquals(real, MrzParser.repairLine2(ocr))
    }

    @Test fun keepsPersonalNumberEndingInC() {
        val real = line2("X1234567", "GBR", "850101", 'F', "290630", "ABC")
        assertEquals(real, MrzParser.repairLine2(real))
    }

    @Test fun emptyPersonalNumberWithFillerCheckDigit() {
        // Some states print '<' as the check digit of an empty personal number.
        val base = line2("123456789", "USA", "800101", 'M', "310101")
        val withFiller = base.substring(0, 42) + "<" + base[43]
        assertTrue(MrzParser.isLine2Valid(withFiller))
    }

    @Test fun lineOneRepairKeepsNameEndingInK() {
        val line = "P<NORERIK<<HANSEN<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        // Here the surname is ERIK; 'K' directly before fillers must survive.
        assertEquals(line, MrzParser.repairLine1(line))
    }

    @Test fun accumulatorNeedsTwoAgreeingFrames() {
        val acc = MrzAccumulator(required = 2)
        val read = MrzParser.parse(l1, l2)!!
        assertNull(acc.add(read))
        assertNotNull(acc.add(read))
    }

    @Test fun datesUseSensibleCentury() {
        val today = LocalDate.of(2026, 11, 10)
        assertEquals(1974, MrzDates.parse("740812", true, today)!!.year)
        assertEquals(2005, MrzDates.parse("050101", true, today)!!.year)
        assertEquals(2031, MrzDates.parse("310101", false, today)!!.year)
        assertFalse(MrzDates.isValid("741312"))
    }
}
