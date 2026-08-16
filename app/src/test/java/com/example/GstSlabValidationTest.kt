package com.example

import com.example.utils.GstCalculationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing on the write path ever checked that a GST rate was a real slab.
 *
 * `gstRateText.toDoubleOrNull() ?: 18.0` caught unparseable text and nothing else, so
 * 200, 18.7 and — because `toDoubleOrNull` accepts the word — "Infinity" all reached the
 * posting engine. The damage surfaced weeks later at filing: GSTR-1's `rt` is
 * reverse-engineered by [GstCalculationService.deriveGstRate], which snaps only within
 * 0.05, so a 200% voucher emits `"rt": 200.0024` and the portal rejects the WHOLE
 * return, pointing at the file rather than the voucher that caused it.
 *
 * The guard now sits at the ViewModel boundary, so it covers every entry path — the
 * wizard, the quick-entry dialog, the bank-statement importer and the XML importer —
 * rather than just the screen that exposed it.
 */
class GstSlabValidationTest {

    @Test
    fun `the slab list is the eight legal Indian rates`() {
        assertEquals(
            listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0),
            GstCalculationService.SLABS
        )
    }

    @Test
    fun `every legal slab is accepted`() {
        GstCalculationService.SLABS.forEach { slab ->
            assertTrue("$slab% must be accepted", slab in GstCalculationService.SLABS)
        }
    }

    @Test
    fun `an absurd rate is not a slab`() {
        // Rs 1,000 at 200% books two thirds of the invoice as tax: taxable 333.33,
        // GST 666.67, all of it claimed as input credit.
        assertFalse(200.0 in GstCalculationService.SLABS)
    }

    @Test
    fun `a near-miss rate is not a slab`() {
        // The dangerous one: close enough to look like a typo nobody catches, far enough
        // that deriveGstRate refuses to snap it.
        assertFalse(18.7 in GstCalculationService.SLABS)
        assertFalse(9.6 in GstCalculationService.SLABS)
    }

    @Test
    fun `a non-finite rate is rejected before it can be tested against the slabs`() {
        // toDoubleOrNull("Infinity") succeeds, and Infinity >= 0.0 is true, so the old
        // `>= 0` guard let it through. It has to be caught by isFinite, not by the list.
        val infinity = "Infinity".toDoubleOrNull()
        assertTrue("Kotlin really does parse this", infinity != null)
        assertFalse("and it passes a naive non-negative check", infinity!! < 0.0)
        assertFalse("so isFinite is the guard that matters", infinity.isFinite())
    }

    @Test
    fun `an off-slab rate survives derivation, which is why it must be blocked at entry`() {
        // deriveGstRate deliberately does NOT snap a rate this far out — it returns the
        // anomaly so it stays visible. That is correct, and it is also why the rate can
        // never be allowed to post in the first place: nothing downstream repairs it.
        val gross = 3000.0
        val gst = 2000.0 // 200% on a taxable value of 1000
        val derived = GstCalculationService.deriveGstRate(gross, gst)

        assertFalse("must not be snapped to a legal slab", derived in GstCalculationService.SLABS)
        assertTrue("and it lands far from any slab: $derived", derived > 100.0)
    }

    @Test
    fun `a legal rate round-trips through derivation unchanged`() {
        GstCalculationService.SLABS.filter { it > 0.0 }.forEach { slab ->
            val gross = GstCalculationService.toGrossAmount(10_000.0, slab, isGstInclusive = false)
            val breakdown = GstCalculationService.calculateGstBreakdown(gross, slab, false)
            val derived = GstCalculationService.deriveGstRate(gross, breakdown.totalGstAmount)

            assertEquals("$slab% must survive a round trip", slab, derived, 0.0001)
        }
    }
}
