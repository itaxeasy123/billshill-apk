package com.example

import com.example.utils.IndianFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Amount in Words" appears on the printed tax invoice beside the figure it describes.
 *
 * It read `amount.toLong()`, so every invoice not ending in .00 printed a words line that
 * silently disagreed with the numerals next to it — and the disagreement crossed the rupee
 * boundary, since truncation and the HALF_UP rounding used by `formatRupee` part company
 * at .995. A negative produced the bare string "Rupees Only", and any amount from Rs 100
 * crore upward threw ArrayIndexOutOfBoundsException while the PDF was being drawn.
 *
 * `formatRupee` has had tests since the money remediation; this function, in the same
 * object, had none. That is why the paise bug outlived its well-tested sibling.
 */
class IndianFormatterWordsTest {

    @Test
    fun `the reported case spells the paise`() {
        assertEquals(
            "Rupees One Thousand Four Hundred Fifty Six and Seventy Eight Paise Only",
            IndianFormatter.convertNumberToWords(1456.78)
        )
    }

    @Test
    fun `whole rupees carry no paise clause`() {
        val words = IndianFormatter.convertNumberToWords(1456.0)
        assertFalse("must not invent a paise clause: $words", words.contains("Paise"))
        assertEquals("Rupees One Thousand Four Hundred Fifty Six Only", words)
    }

    @Test
    fun `the words agree with the figure printed beside them`() {
        // The invariant that actually matters: both appear on one page of a tax invoice.
        // Truncation vs HALF_UP is exactly where they used to part company.
        listOf(1456.78, 1456.995, 19_262.00, 100.455, 21_573.44 / 1.12).forEach { v ->
            val words = IndianFormatter.convertNumberToWords(v)
            val rupeesInFigure = java.math.BigDecimal(v.toString())
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toBigInteger()
                .toLong()
            val expectedRupeeWords = IndianFormatter.convertNumberToWords(rupeesInFigure.toDouble())
                .removePrefix("Rupees ")
                .removeSuffix(" Only")
            assertTrue(
                "words and figure disagree for $v: '$words' vs rupees=$rupeesInFigure",
                words.contains(expectedRupeeWords)
            )
        }
    }

    @Test
    fun `indian grouping boundaries`() {
        assertEquals("Rupees One Lakh Only", IndianFormatter.convertNumberToWords(100_000.0))
        assertEquals("Rupees One Crore Only", IndianFormatter.convertNumberToWords(10_000_000.0))
        assertEquals(
            "Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven Only",
            IndianFormatter.convertNumberToWords(1_234_567.0)
        )
    }

    @Test
    fun `a hundred crore does not throw`() {
        // The old helper indexed tens[n / 10] off the end of a 10-element array for any
        // n >= 100, and the crore group was handed to it unbounded. An extra-zeros typo
        // in an amount crashed the PDF.
        val big = IndianFormatter.convertNumberToWords(1_000_000_000.0)
        assertTrue("must spell, not throw: $big", big.contains("Crore"))
        assertTrue(IndianFormatter.convertNumberToWords(1e11).isNotBlank())
    }

    @Test
    fun `a negative is spelled rather than silently emptied`() {
        val words = IndianFormatter.convertNumberToWords(-1456.78)
        assertTrue("must say so: $words", words.startsWith("Minus Rupees"))
        assertTrue(words.contains("Fifty Six"))
        assertFalse("the old output was the bare string", words == "Rupees Only")
    }

    @Test
    fun `a sub-rupee amount is all paise`() {
        assertEquals("Rupees Zero and Five Paise Only", IndianFormatter.convertNumberToWords(0.05))
        assertEquals(
            "Rupees Zero and Seventy Eight Paise Only",
            IndianFormatter.convertNumberToWords(0.78)
        )
    }

    @Test
    fun `zero and non-finite are stated, not crashed`() {
        assertEquals("Rupees Zero Only", IndianFormatter.convertNumberToWords(0.0))
        assertEquals("Rupees Zero Only", IndianFormatter.convertNumberToWords(Double.NaN))
        assertEquals("Rupees Zero Only", IndianFormatter.convertNumberToWords(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `it never throws and is always well-formed over a wide sweep`() {
        var v = 0.01
        var checked = 0
        while (v < 1_000_000_000.0) {
            val words = IndianFormatter.convertNumberToWords(v)
            assertTrue("bad prefix at $v: $words", words.startsWith("Rupees"))
            assertTrue("bad suffix at $v: $words", words.endsWith("Only"))
            assertFalse("double space at $v: $words", words.contains("  "))
            checked++
            v *= 1.7
        }
        assertTrue("the sweep must actually have run", checked > 40)
    }

    @Test
    fun `quantities keep their fractional part`() {
        assertEquals("12.75", IndianFormatter.formatQuantity(12.75))
        assertEquals("12", IndianFormatter.formatQuantity(12.0))
        assertEquals("-0.5", IndianFormatter.formatQuantity(-0.5))
        assertEquals("-", IndianFormatter.formatQuantity(Double.NaN))
    }
}
