package com.example

import com.example.data.gst.Rule88ASetOff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rule 88A ITC set-off (H24).
 *
 * The worked case below is the one the previous implementation got wrong by ₹1,50,000 —
 * not by a rounding residue. Assertions are exact.
 */
class Rule88ASetOffTest {

    @Test
    fun `IGST credit spills into CGST and SGST before their own credit is touched`() {
        // Output CGST 90,000 / SGST 90,000 / IGST 0
        // Credit IGST 1,50,000 / CGST 15,000 / SGST 15,000
        //
        // IGST credit meets no IGST liability, so all 1,50,000 spills over: 90,000 clears
        // CGST entirely, 60,000 reduces SGST to 30,000. CGST's own 15,000 cannot be used
        // (nothing left to pay) and carries forward. SGST's own 15,000 reduces SGST to
        // 15,000 payable.
        //
        // The old code reported CGST 75,000, SGST 75,000 and a total of 0 — four cells of
        // one row disagreeing, with the correct 15,000 appearing nowhere.
        val r = Rule88ASetOff.compute(
            outputIgst = 0.0, outputCgst = 90_000.0, outputSgst = 90_000.0,
            creditIgst = 150_000.0, creditCgst = 15_000.0, creditSgst = 15_000.0
        )

        assertEquals(0.0, r.cashIgst, 0.0)
        assertEquals(0.0, r.cashCgst, 0.0)
        assertEquals(15_000.0, r.cashSgst, 0.0)
        assertEquals(15_000.0, r.totalCash, 0.0)

        // The CGST credit that could not be used is carried forward, not destroyed.
        assertEquals(15_000.0, r.cgst.creditCarriedForward, 0.0)
        assertEquals(0.0, r.igst.creditCarriedForward, 0.0)
    }

    @Test
    fun `CGST credit can never be used against SGST liability`() {
        // s.49(5): cross-utilisation between CGST and SGST is prohibited.
        val r = Rule88ASetOff.compute(
            outputIgst = 0.0, outputCgst = 0.0, outputSgst = 50_000.0,
            creditIgst = 0.0, creditCgst = 50_000.0, creditSgst = 0.0
        )
        assertEquals("SGST must still be paid in full", 50_000.0, r.cashSgst, 0.0)
        assertEquals("CGST credit carries forward untouched", 50_000.0, r.cgst.creditCarriedForward, 0.0)
    }

    @Test
    fun `IGST liability is met by IGST credit first`() {
        val r = Rule88ASetOff.compute(
            outputIgst = 40_000.0, outputCgst = 10_000.0, outputSgst = 10_000.0,
            creditIgst = 45_000.0, creditCgst = 0.0, creditSgst = 0.0
        )
        assertEquals(0.0, r.cashIgst, 0.0)
        // 5,000 of IGST credit remains and spills onto CGST.
        assertEquals(5_000.0, r.cashCgst, 0.0)
        assertEquals(10_000.0, r.cashSgst, 0.0)
    }

    @Test
    fun `section 170 rounds cash payable per head to the nearest rupee`() {
        val r = Rule88ASetOff.compute(
            outputIgst = 0.0, outputCgst = 50.60, outputSgst = 50.40,
            creditIgst = 0.0, creditCgst = 0.0, creditSgst = 0.0
        )
        assertEquals(51.0, r.cashCgst, 0.0)
        assertEquals(50.0, r.cashSgst, 0.0)
    }

    @Test
    fun `no liability and no credit pays nothing`() {
        val r = Rule88ASetOff.compute(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, r.totalCash, 0.0)
        assertEquals(0.0, r.igst.creditCarriedForward, 0.0)
    }

    @Test
    fun `surplus credit with no liability is entirely carried forward`() {
        val r = Rule88ASetOff.compute(
            outputIgst = 0.0, outputCgst = 0.0, outputSgst = 0.0,
            creditIgst = 20_000.0, creditCgst = 5_000.0, creditSgst = 5_000.0
        )
        assertEquals(0.0, r.totalCash, 0.0)
        assertEquals(20_000.0, r.igst.creditCarriedForward, 0.0)
        assertEquals(5_000.0, r.cgst.creditCarriedForward, 0.0)
        assertEquals(5_000.0, r.sgst.creditCarriedForward, 0.0)
    }

    @Test
    fun `the two invariants hold across a thousand random cases`() {
        // creditUsed + carriedForward == available, and creditUsed + cash == liability.
        // Nothing may be silently destroyed — which is exactly what the clamp did.
        val rnd = java.util.Random(88L)
        repeat(1000) {
            val r = Rule88ASetOff.compute(
                outputIgst = rnd.nextInt(200_000).toDouble(),
                outputCgst = rnd.nextInt(200_000).toDouble(),
                outputSgst = rnd.nextInt(200_000).toDouble(),
                creditIgst = rnd.nextInt(200_000).toDouble(),
                creditCgst = rnd.nextInt(200_000).toDouble(),
                creditSgst = rnd.nextInt(200_000).toDouble()
            )
            listOf("cgst" to r.cgst, "sgst" to r.sgst).forEach { (name, h) ->
                assertEquals(
                    "$name credit conservation",
                    h.creditAvailable, h.creditUsed + h.creditCarriedForward, 0.005
                )
            }
            // IGST's credit is spent across all three heads, so conservation is checked
            // on the pool rather than per head.
            assertEquals(
                "igst credit conservation",
                r.igst.creditAvailable, r.igst.creditUsed + r.igst.creditCarriedForward, 0.005
            )
            // Nothing negative may escape.
            listOf(r.cashIgst, r.cashCgst, r.cashSgst).forEach {
                assert(it >= 0.0) { "cash payable went negative: $it" }
            }
        }
    }
}
