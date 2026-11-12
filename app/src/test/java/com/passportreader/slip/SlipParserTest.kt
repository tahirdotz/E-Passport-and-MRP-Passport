package com.passportreader.slip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Layout modelled on a real delivery slip; all personal data here is invented. */
class SlipParserTest {

    private val today = LocalDate.of(2026, 11, 13)

    private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrLine(text, left, top, right, bottom)

    private fun sampleSlip(
        appId: String = "123456",
        printedBarcode: String = "260300000123456",
        collection: String = "Tentative Collection Date: 21/09/2020",
        givenName: String? = "RAHIM KARIM",
        nid: String = "1234567890",
    ) = buildList {
        add(l(appId, 29, 27, 90, 42))
        add(l("Enrolment Date: 10/09/2020", 31, 47, 161, 59))
        add(l(collection, 31, 63, 210, 78))
        add(l("RPO Name: AGARGAON", 31, 81, 151, 93))
        add(l("Mobile No: 01700000000", 31, 99, 151, 109))
        add(l(printedBarcode, 581, 43, 669, 53))
        add(l("Passport Type: ORDINARY", 545, 57, 679, 69))
        add(l("Delivery Type :EXPRESS", 545, 72, 662, 89))
        add(l("Crft / Type", 113, 149, 167, 163))
        add(l("ORDINARY BGD", 115, 167, 203, 179))
        add(l("4s RI/ Surname", 115, 185, 217, 195))
        add(l("UDDIN", 115, 199, 151, 209))
        add(l("9 / Given Name", 117, 217, 221, 227))
        if (givenName != null) add(l(givenName, 115, 231, 215, 243))
        add(l("Sratrat / Nationalty", 117, 247, 211, 259))
        add(l("BANGLADESHI", 117, 263, 191, 275))
        add(l("S Sifiru / Date of Birth", 117, 279, 231, 293))
        add(l("29/04/1998", 115, 297, 169, 307))
        add(l("asfams at / Personal No", 259, 249, 371, 261))
        add(l(nid, 257, 267, 315, 277))
        add(l("Sa / Place of Birth", 257, 313, 357, 325))
        add(l("DHAKA", 256, 327, 294, 341))
        add(l("INFORMATION OF THE BEARER", 495, 153, 679, 169))
        add(l("Name:", 453, 168, 486, 182))
        add(l(listOfNotNull(givenName, "UDDIN").joinToString(" "), 559, 169, 695, 181))
        add(l("Father's Name", 453, 185, 521, 197))
        add(l("ABDUL UDDIN", 559, 185, 704, 201))
        add(l("Emergency Contact:", 453, 251, 545, 263))
        add(l("Name:", 453, 265, 485, 275))
        add(l("SOMEONE ELSE", 557, 265, 663, 277))
        add(l("Bank Name", 87, 419, 135, 429))
    }

    @Test fun readsEveryFieldFromOneFrame() {
        val read = SlipParser.parse(sampleSlip(), barcodes = emptyList(), today = today).fields
        assertEquals("123456", read[SlipField.APPLICATION_ID]?.value)
        assertEquals("260300000123456", read[SlipField.BARCODE]?.value)
        assertEquals("RAHIM KARIM UDDIN", read[SlipField.NAME]?.value)
        assertEquals("ORDINARY", read[SlipField.PASSPORT_TYPE]?.value)
        assertEquals("EXPRESS", read[SlipField.DELIVERY_TYPE]?.value)
        assertEquals("21/09/2020", read[SlipField.COLLECTION_DATE]?.value)
        assertEquals("1234567890", read[SlipField.NID]?.value)
        assertEquals("29/04/1998", read[SlipField.DATE_OF_BIRTH]?.value)
        // Everything cross-checks, so a camera can finish after a single frame.
        assertTrue(read.values.all { it.confirmed })
    }

    @Test fun scannerBarcodeWinsOverMisreadPrintedNumber() {
        val lines = sampleSlip(printedBarcode = "260300000123455") // OCR got the last digit wrong
        val ocrOnly = SlipParser.parse(lines, emptyList(), today).fields
        assertFalse(ocrOnly[SlipField.APPLICATION_ID]!!.confirmed)
        assertFalse(ocrOnly[SlipField.BARCODE]!!.confirmed)

        val withScanner = SlipParser.parse(lines, listOf("260300000123456"), today).fields
        assertEquals("260300000123456", withScanner[SlipField.BARCODE]?.value)
        assertTrue(withScanner[SlipField.BARCODE]!!.confirmed)
        assertTrue(withScanner[SlipField.APPLICATION_ID]!!.confirmed)
    }

    @Test fun emptyGivenNameDoesNotGrabTheNextLabel() {
        val read = SlipParser.parse(sampleSlip(givenName = null), emptyList(), today).fields
        assertEquals("UDDIN", read[SlipField.NAME]?.value)
        assertTrue(read[SlipField.NAME]!!.confirmed)
    }

    @Test fun picksBearerNameNotEmergencyContact() {
        val read = SlipParser.parse(sampleSlip(), emptyList(), today).fields
        assertFalse(read[SlipField.NAME]!!.value.contains("SOMEONE"))
    }

    @Test fun penMarkOverYearIsCompletedFromEnrolmentDate() {
        val lines = sampleSlip(collection = "Tentative Coliection Date") +
            l("/28/09/20šșš", 212, 61, 260, 77)
        val f = SlipParser.parse(lines, emptyList(), today).fields[SlipField.COLLECTION_DATE]!!
        assertEquals("28/09/2020", f.value)
        assertFalse(f.confirmed)
    }

    @Test fun seventeenDigitNidMustStartWithBirthYear() {
        val good = SlipParser.parse(sampleSlip(nid = "19982613338000059"), emptyList(), today).fields[SlipField.NID]!!
        assertTrue(good.confirmed)
        val bad = SlipParser.parse(sampleSlip(nid = "19852613338000059"), emptyList(), today).fields[SlipField.NID]!!
        assertFalse(bad.confirmed)
    }

    @Test fun deliveryTypeVariants() {
        fun type(line: String) = SlipParser.parse(
            sampleSlip().filterNot { it.text.startsWith("Delivery Type") } + l(line, 545, 72, 662, 89), emptyList(), today,
        ).fields[SlipField.DELIVERY_TYPE]
        assertEquals("REGULAR", type("Delivery Type REGULAR")?.value)       // no colon
        assertEquals("SUPER EXPRESS", type("Delivery Type: SUPEREXPRESS")?.value) // space lost by OCR
        assertEquals("EXPRESS", type("Delivery Type :EXPRES")?.value)       // letter dropped
        assertTrue(type("Delivery Type: REGULAR")!!.confirmed)
    }

    @Test fun rejectsNidOfWrongLength() {
        val read = SlipParser.parse(sampleSlip(nid = "12345678901"), emptyList(), today).fields
        assertEquals(null, read[SlipField.NID])
    }

    @Test fun ocrLookalikesInNumbersAreFixed() {
        assertEquals("1234567890", SlipParser.digitsOf("l23456789O"))
        assertEquals(LocalDate.of(1986, 1, 1), SlipParser.findDate("01/01/1986ł"))
        assertEquals(LocalDate.of(2020, 9, 21), SlipParser.findDate("2l/O9/2020"))
    }

    @Test fun accumulatorTakesConfirmedAtOnceAndUnconfirmedOnAgreement() {
        val acc = SlipAccumulator()
        val confirmed = SlipRead(mapOf(SlipField.DATE_OF_BIRTH to FieldRead("29/04/1998", true)))
        acc.add(confirmed)
        assertEquals("29/04/1998", acc.fields[SlipField.DATE_OF_BIRTH]?.value)

        val unsure = SlipRead(mapOf(SlipField.NAME to FieldRead("RAHIM", false)))
        acc.add(unsure)
        assertEquals(null, acc.fields[SlipField.NAME])
        acc.add(unsure)
        assertEquals("RAHIM", acc.fields[SlipField.NAME]?.value)
    }

    @Test fun accumulatorResetsWhenAnotherSlipAppears() {
        val acc = SlipAccumulator()
        acc.add(SlipRead(mapOf(SlipField.BARCODE to FieldRead("111111111111111", true), SlipField.NID to FieldRead("1234567890", true))))
        acc.add(SlipRead(mapOf(SlipField.BARCODE to FieldRead("222222222222222", true))))
        assertEquals("222222222222222", acc.fields[SlipField.BARCODE]?.value)
        assertEquals(null, acc.fields[SlipField.NID])
    }

    /**
     * Optional check against real slips: set SLIP_FIXTURES to a folder of TSV files
     * (text, left, top, right, bottom per line). Kept outside the repo because real slips hold personal data.
     */
    @Test fun realFixturesIfProvided() {
        val dir = System.getenv("SLIP_FIXTURES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { x -> x.name.endsWith(".tsv") }!!.sorted()) {
            val lines = f.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.mapNotNull { row ->
                val p = row.split('\t')
                if (p.size < 5) null else OcrLine(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
            }
            val read = SlipParser.parse(lines, emptyList(), today)
            println("== ${f.name}")
            for (field in SlipField.entries) {
                val v = read.fields[field]
                println("  ${field.label}: ${v?.value ?: "—"}${if (v != null && !v.confirmed) "  (unconfirmed${v.note?.let { ": $it" } ?: ""})" else ""}")
            }
        }
    }
}
