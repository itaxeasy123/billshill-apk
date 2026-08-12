package com.example

import com.example.utils.GstCalculationService
import com.example.utils.IndianFormatter
import com.example.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money precision.
 *
 * Assertions here are EXACT — delta 0.0, or string equality. The existing GST tests use
 * a 0.005 tolerance, which is half a paisa, and that is precisely why none of these
 * defects were caught: every one of them hides inside half a paisa or inside a
 * string-formatting step no numeric assertion ever looked at.
 */
class MoneyPrecisionTest {

    // ---- the rendering bug (live, on printed tax invoices) ----

    @Test
    fun `an exact rupee amount does not render with three paise digits`() {
        // 21573.44 / 1.12 is exactly 19262.00. The formatter truncated the rupee part
        // while rounding the paise part, producing "19,261.100".
        assertEquals("₹19,262.00", IndianFormatter.formatRupee(21573.44 / 1.12))
        assertEquals("₹1,28,841.00", IndianFormatter.formatRupee(135283.05 / 1.05))
        assertEquals("₹1,09,468.00", IndianFormatter.formatRupee(122604.16 / 1.12))
    }

    @Test
    fun `paise field is always exactly two digits`() {
        var checked = 0
        var rate = 5.0
        listOf(5.0, 12.0, 18.0, 28.0).forEach { r ->
            rate = r
            for (i in 1..3000) {
                val gross = i * 7.13
                val rendered = IndianFormatter.formatRupee(gross / (1 + rate / 100.0))
                val paise = rendered.substringAfterLast('.')
                assertEquals("two paise digits for $rendered", 2, paise.length)
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `formatter rounds half up rather than truncating`() {
        assertEquals("₹100.46", IndianFormatter.formatRupee(100.455))
        assertEquals("₹100.44", IndianFormatter.formatRupee(100.444))
    }

    // ---- the split-reconciliation bug ----

    @Test
    fun `CGST plus SGST always equals the total GST exactly`() {
        // Each half used to be rounded independently, so on odd-paise bases they did not
        // add up: a 1,000.05 base at 18% gave 90.00 + 90.00 = 180.00 against a total of
        // 180.01, and the missing paisa went nowhere.
        listOf(1_000.05, 333.33, 1_250.05, 99.99, 7.77).forEach { base ->
            listOf(5.0, 12.0, 18.0, 28.0).forEach { rate ->
                val gross = GstCalculationService.toGrossAmount(base, rate, isGstInclusive = false)
                val b = GstCalculationService.calculateGstBreakdown(gross, rate, isInterstate = false)
                // Reconciles to the paisa. The halves used to be rounded independently
                // from the raw value, so they could differ from the total by a full
                // paisa; they are now derived from it. The residual tolerance below is
                // binary addition noise, ~1e-13, not a rounding error.
                assertEquals(
                    "cgst+sgst must reconcile to gst for $base @ $rate%",
                    b.totalGstAmount, b.cgstAmount + b.sgstAmount, 1e-9
                )
                assertTrue(Money.isQuantised(b.cgstAmount) && Money.isQuantised(b.sgstAmount))
            }
        }
    }

    @Test
    fun `taxable plus GST always equals the gross exactly`() {
        listOf(1_000.05, 11_800.0, 333.33, 45_678.91).forEach { gross ->
            listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { rate ->
                val b = GstCalculationService.calculateGstBreakdown(gross, rate, isInterstate = false)
                assertEquals(
                    "taxable+gst must reconcile to gross for $gross @ $rate%",
                    b.grossAmount, b.taxableValue + b.totalGstAmount, 1e-9
                )
                assertTrue(Money.isQuantised(b.taxableValue) && Money.isQuantised(b.totalGstAmount))
            }
        }
    }

    @Test
    fun `the journal legs of an interstate sale sum exactly to the invoice total`() {
        val b = GstCalculationService.calculateGstBreakdown(11_800.0, 18.0, isInterstate = true)
        assertEquals(b.grossAmount, b.taxableValue + b.igstAmount, 1e-9)
        assertEquals(0.0, b.cgstAmount, 0.0)
        assertEquals(0.0, b.sgstAmount, 0.0)
    }

    // ---- everything reaching the GSTR JSON must be 2dp ----

    @Test
    fun `every figure is exactly 2dp and the legs still reconcile`() {
        // The trade-off, made explicitly: a residual like `gross - gst` is not always
        // itself representable at 2dp (577.71 - 165.06 = 412.65000000000003), so every
        // component is quantised and the identities hold to ~1e-13 of binary addition
        // noise rather than to zero.
        //
        // That is the right way round. 2dp is what the GSTR schema validates and what a
        // printed invoice shows, whereas the journal-imbalance banner triggers at
        // Rs 0.01 — eleven orders of magnitude above the residue.
        val rnd = java.util.Random(20260813L)
        repeat(4000) {
            val gross = (rnd.nextInt(5_000_00) + 1) / 100.0
            val rate = listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0)[rnd.nextInt(7)]
            val interstate = rnd.nextBoolean()
            val b = GstCalculationService.calculateGstBreakdown(gross, rate, interstate)

            listOf(
                b.grossAmount to "gross", b.totalGstAmount to "gst",
                b.cgstAmount to "cgst", b.sgstAmount to "sgst", b.igstAmount to "igst"
            ).forEach { (v, name) ->
                assertTrue("$name=$v not 2dp for gross=$gross rate=$rate", Money.isQuantised(v))
            }
            assertTrue("taxable=${b.taxableValue} not 2dp", Money.isQuantised(b.taxableValue))
            // Legs reconcile far below the Rs 0.01 the imbalance banner reacts to.
            assertEquals(b.grossAmount, b.taxableValue + b.totalGstAmount, 1e-9)
            assertEquals(b.totalGstAmount, b.cgstAmount + b.sgstAmount + b.igstAmount, 1e-9)
        }
    }

    @Test
    fun `an exclusive entry posts legs that sum exactly to the stored total`() {
        // The party leg is debited with the value toGrossAmount returns, while the sales
        // and tax legs come from the breakdown. If the two disagree, every voucher leaves
        // a residue on the party's ledger that no receipt can settle, and enough of them
        // trip the "your books do not balance" banner.
        listOf(1_234.56, 999.99, 10_000.0, 45_678.91, 7.77).forEach { base ->
            listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { rate ->
                val gross = GstCalculationService.toGrossAmount(base, rate, isGstInclusive = false)
                val b = GstCalculationService.calculateGstBreakdown(gross, rate, isInterstate = false)
                val legs = b.taxableValue + b.cgstAmount + b.sgstAmount + b.igstAmount
                assertEquals("legs vs stored total for $base @ $rate%", gross, legs, 1e-9)
                assertTrue("stored total must be 2dp", Money.isQuantised(gross))
            }
        }
    }

    @Test
    fun `a non-finite amount is rejected rather than crashing`() {
        // "NaN".toDoubleOrNull() succeeds in Kotlin and NaN passes an `amount <= 0`
        // guard, so it reached BigDecimal("NaN") and threw NumberFormatException.
        assertEquals(0.0, Money.paise(Double.NaN), 0.0)
        assertEquals(0.0, Money.paise(Double.POSITIVE_INFINITY), 0.0)
    }

    // ---- the helper itself ----

    @Test
    fun `paise rounds half up and never carries binary noise`() {
        assertEquals(0.15, Money.paise(0.145), 0.0)
        assertEquals(1.01, Money.paise(1.005), 0.0)
        assertEquals(762.71, Money.paise(762.7118644067797), 0.0)
    }

    @Test
    fun `rupees applies section 170 rounding`() {
        // "…fifty paise or more shall be increased to one rupee; less than fifty paise
        // shall be ignored."
        assertEquals(51.0, Money.rupees(50.60), 0.0)
        assertEquals(50.0, Money.rupees(50.40), 0.0)
        assertEquals(51.0, Money.rupees(50.50), 0.0)
    }

    @Test
    fun `quantised values survive a round trip through Double storage`() {
        // Justifies keeping REAL columns instead of a table rebuild to TEXT.
        val rnd = java.util.Random(7L)
        repeat(20_000) {
            val v = Money.paise((rnd.nextInt(90_000_00)) / 100.0)
            assertEquals(v, Money.paise(v), 0.0)
        }
    }
}
