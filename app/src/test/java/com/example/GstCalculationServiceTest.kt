package com.example

import com.example.utils.GstCalculationService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for C8 — the Exclusive-GST short-collection.
 *
 * Before the fix, `addVoucher` had no inclusive/exclusive parameter and
 * `calculateGstBreakdown` treated every amount as tax-inclusive. Entering ₹10,000 in
 * the wizard's default Exclusive mode posted a ₹10,000 voucher carrying ₹1,525.42 of
 * GST instead of an ₹11,800 voucher carrying ₹1,800 — every invoice on the app's own
 * default path under-collected by 15.25%.
 */
class GstCalculationServiceTest {

    private val tolerance = 0.005

    @Test
    fun `exclusive entry is grossed up before posting`() {
        // ₹10,000 base + 18% must reach the repository as ₹11,800.
        val gross = GstCalculationService.toGrossAmount(10_000.0, 18.0, isGstInclusive = false)
        assertEquals(11_800.0, gross, tolerance)
    }

    @Test
    fun `inclusive entry passes through untouched`() {
        val gross = GstCalculationService.toGrossAmount(11_800.0, 18.0, isGstInclusive = true)
        assertEquals(11_800.0, gross, tolerance)
    }

    @Test
    fun `exclusive and inclusive entries of the same sale post identically`() {
        // The whole point of the fix: ₹10,000 Exclusive and ₹11,800 Inclusive are the
        // same transaction and must produce the same journal.
        val fromExclusive = GstCalculationService.calculateGstBreakdown(
            GstCalculationService.toGrossAmount(10_000.0, 18.0, isGstInclusive = false), 18.0, false
        )
        val fromInclusive = GstCalculationService.calculateGstBreakdown(
            GstCalculationService.toGrossAmount(11_800.0, 18.0, isGstInclusive = true), 18.0, false
        )
        assertEquals(fromInclusive.taxableValue, fromExclusive.taxableValue, tolerance)
        assertEquals(fromInclusive.totalGstAmount, fromExclusive.totalGstAmount, tolerance)
        assertEquals(fromInclusive.grossAmount, fromExclusive.grossAmount, tolerance)
    }

    @Test
    fun `intrastate sale splits into equal CGST and SGST`() {
        val gross = GstCalculationService.toGrossAmount(10_000.0, 18.0, isGstInclusive = false)
        val b = GstCalculationService.calculateGstBreakdown(gross, 18.0, isInterstate = false)

        assertEquals(10_000.0, b.taxableValue, tolerance)
        assertEquals(1_800.0, b.totalGstAmount, tolerance)
        assertEquals(900.0, b.cgstAmount, tolerance)
        assertEquals(900.0, b.sgstAmount, tolerance)
        assertEquals(0.0, b.igstAmount, tolerance)
    }

    @Test
    fun `interstate sale puts the whole tax in IGST`() {
        val gross = GstCalculationService.toGrossAmount(10_000.0, 18.0, isGstInclusive = false)
        val b = GstCalculationService.calculateGstBreakdown(gross, 18.0, isInterstate = true)

        assertEquals(10_000.0, b.taxableValue, tolerance)
        assertEquals(1_800.0, b.igstAmount, tolerance)
        assertEquals(0.0, b.cgstAmount, tolerance)
        assertEquals(0.0, b.sgstAmount, tolerance)
    }

    @Test
    fun `zero-rated supply is never grossed up`() {
        // Exempt/nil-rated goods: a 0% rate must leave the amount alone in either mode.
        assertEquals(5_000.0, GstCalculationService.toGrossAmount(5_000.0, 0.0, false), tolerance)
        assertEquals(5_000.0, GstCalculationService.toGrossAmount(5_000.0, 0.0, true), tolerance)
    }

    @Test
    fun `every slab round-trips base to gross and back`() {
        // Guards the arithmetic across the full slab set, including the 40% de-merit
        // rate the app does not yet offer but will need.
        listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { rate ->
            val gross = GstCalculationService.toGrossAmount(10_000.0, rate, isGstInclusive = false)
            val b = GstCalculationService.calculateGstBreakdown(gross, rate, isInterstate = false)
            assertEquals("taxable value at $rate%", 10_000.0, b.taxableValue, tolerance)
            assertEquals("tax at $rate%", 10_000.0 * rate / 100.0, b.totalGstAmount, tolerance)
        }
    }

    // ---- rate recovery on edit (H5/H26) ----

    @Test
    fun `the rate a voucher was charged at is recovered, not guessed as 18`() {
        // Both edit dialogs seeded `if (gstAmount > 0) "18" else "0"`, so opening a 5%
        // invoice and saving rewrote it to 18% — silently, on a posted voucher.
        listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { rate ->
            val gross = GstCalculationService.toGrossAmount(10_000.0, rate, isGstInclusive = false)
            val b = GstCalculationService.calculateGstBreakdown(gross, rate, isInterstate = false)
            assertEquals(
                "rate recovered for $rate%",
                rate,
                GstCalculationService.deriveGstRate(b.grossAmount, b.totalGstAmount),
                0.001
            )
        }
    }

    @Test
    fun `a zero-rated voucher recovers as zero, not as 18`() {
        assertEquals(0.0, GstCalculationService.deriveGstRate(5_000.0, 0.0), 0.001)
    }

    @Test
    fun `rate recovery snaps through stored rounding noise`() {
        // Amounts come back out of SQLite as Doubles; the snap absorbs the drift rather
        // than producing an unusable 17.999999 rate.
        assertEquals(18.0, GstCalculationService.deriveGstRate(11_800.004, 1_800.0), 0.001)
    }

    @Test
    fun `empty amount is not grossed up into a phantom value`() {
        assertEquals(0.0, GstCalculationService.toGrossAmount(0.0, 18.0, false), tolerance)
    }
}
