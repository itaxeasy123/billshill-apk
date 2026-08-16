package com.example

import com.example.utils.GstCalculationService
import com.example.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every place that shows or files a GST split derives it from
 * [GstCalculationService.splitHeads]. Eleven sites used to halve the total themselves
 * with `gstAmount / 2.0`, twice, which had two consequences:
 *
 *  * the halves were rounded independently, so CGST + SGST could exceed the tax total
 *    printed on the same invoice by a paisa; and
 *  * the unrounded values reached the GSTR JSON, where every monetary field is capped at
 *    two decimals and anything longer is rejected by the portal.
 *
 * The printed invoice and the filed return both go through this now, which is the pair
 * that matters: a discrepancy between a tax invoice and the return filed against it is
 * the one an assessment compares.
 */
class GstSplitConsistencyTest {

    /** The case the whole rounding remediation was written for: Rs 1,000.05 base at 18%. */
    private val awkwardGst = 180.01

    @Test
    fun `the two heads always add back to the total exactly`() {
        val (cgst, sgst, igst) = GstCalculationService.splitHeads(awkwardGst, isInterstate = false)

        assertEquals("CGST takes the odd paisa", 90.01, cgst, 0.0)
        assertEquals("SGST is the residual", 90.00, sgst, 0.0)
        assertEquals(0.0, igst, 0.0)
        assertEquals("and they must reconcile to the total", awkwardGst, cgst + sgst, 0.0)
    }

    @Test
    fun `halving twice is what used to break it`() {
        // Guards the regression directly: if anyone reverts to `total / 2.0` twice, the
        // sum below stops equalling the total and this fails.
        val naive = awkwardGst / 2.0
        assertTrue(
            "the naive split must not be 2dp — that is why it was rejected",
            !Money.isQuantised(naive)
        )
        val (cgst, sgst, _) = GstCalculationService.splitHeads(awkwardGst, false)
        assertTrue("the real split must be 2dp", Money.isQuantised(cgst) && Money.isQuantised(sgst))
    }

    @Test
    fun `a 152 point 55 tax splits the way the ledger posted it`() {
        val (cgst, sgst, _) = GstCalculationService.splitHeads(152.55, false)

        assertEquals(76.28, cgst, 0.0)
        assertEquals(76.27, sgst, 0.0)
        assertEquals("the printed invoice cannot exceed its own tax total", 152.55, cgst + sgst, 0.0)
    }

    @Test
    fun `an interstate supply is all IGST and no state heads`() {
        // A naive fix that read cgstAmount/sgstAmount from storage would have printed
        // 0.00 / 0.00 here and dropped IGST entirely — the tax vanishing from the face of
        // the document while the grand total still included it.
        val (cgst, sgst, igst) = GstCalculationService.splitHeads(1_800.0, isInterstate = true)

        assertEquals(0.0, cgst, 0.0)
        assertEquals(0.0, sgst, 0.0)
        assertEquals(1_800.0, igst, 0.0)
    }

    @Test
    fun `the heads reconcile for every slab over a wide range of bases`() {
        val slabs = listOf(0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0)
        var checked = 0

        for (slab in slabs) {
            var base = 0.01
            while (base < 5_000.0) {
                val breakdown = GstCalculationService.calculateGstBreakdown(
                    totalAmountInclusive = GstCalculationService.toGrossAmount(base, slab, false),
                    gstRatePercentage = slab,
                    isInterstate = false
                )
                val total = breakdown.totalGstAmount
                val (cgst, sgst, igst) = GstCalculationService.splitHeads(total, false)

                // Tolerance, not bit-equality: each head is exactly 2dp, but ADDING two
                // 2dp doubles can land a few ulps off (0.11 + 0.10 == 0.21000000000000002).
                // That noise is ~1e-17 — sixteen orders of magnitude below a paisa, and it
                // never reaches a stored or printed figure, because what gets rendered is
                // each quantised head and the voucher's own gstAmount, never this sum.
                assertEquals(
                    "heads must reconcile at base=$base slab=$slab",
                    total, cgst + sgst + igst, 1e-9
                )
                assertTrue("CGST must be 2dp at base=$base slab=$slab", Money.isQuantised(cgst))
                assertTrue("SGST must be 2dp at base=$base slab=$slab", Money.isQuantised(sgst))
                checked++
                base += 7.13
            }
        }
        assertTrue("the sweep must actually have run", checked > 3_000)
    }

    @Test
    fun `a posted voucher's split is a pure function of its own header`() {
        // This is why no site needs to read gst_tax_details: the split depends only on
        // gstAmount and isInterstate, both of which sit on the row being rendered. That
        // keeps every renderer synchronous and repairs vouchers posted before this
        // convention existed, whose stored rows still hold the old unquantised halves.
        val fromHeader = GstCalculationService.splitForVoucher(awkwardGst, false)
        val fromEngine = GstCalculationService.splitHeads(awkwardGst, false)

        assertEquals(fromEngine, fromHeader)
    }

    @Test
    fun `the split matches what the posting engine stored`() {
        // Same number, arrived at two ways: through the full breakdown that writes
        // gst_tax_details, and through the helper every renderer now calls.
        val breakdown = GstCalculationService.calculateGstBreakdown(
            totalAmountInclusive = 1_180.06, gstRatePercentage = 18.0, isInterstate = false
        )
        val (cgst, sgst, igst) =
            GstCalculationService.splitForVoucher(breakdown.totalGstAmount, breakdown.isInterstate)

        assertEquals("the invoice must show the CGST the ledger posted", breakdown.cgstAmount, cgst, 0.0)
        assertEquals("and the SGST", breakdown.sgstAmount, sgst, 0.0)
        assertEquals(breakdown.igstAmount, igst, 0.0)
    }

    @Test
    fun `taxable value is quantised rather than subtracted raw`() {
        // `totalAmount - gstAmount` as a raw Double put values like 762.7118644067797
        // into txval, which the portal rejects for the same reason as the split.
        val taxable = GstCalculationService.taxableValueOf(900.0, 137.29)

        assertTrue("must be 2dp", Money.isQuantised(taxable))
        assertEquals(762.71, taxable, 0.0)
    }

    @Test
    fun `a zero-tax voucher splits to zero, not to a stray paisa`() {
        val (cgst, sgst, igst) = GstCalculationService.splitHeads(0.0, false)
        assertEquals(0.0, cgst, 0.0)
        assertEquals(0.0, sgst, 0.0)
        assertEquals(0.0, igst, 0.0)
    }
}
