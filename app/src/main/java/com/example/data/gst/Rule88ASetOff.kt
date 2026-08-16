package com.example.data.gst

import com.example.utils.Money

/** What one tax head owes and what credit it has left, after set-off. */
data class HeadResult(
    val liability: Double,
    val creditAvailable: Double,
    val creditUsed: Double,
    val payableInCash: Double,
    val creditCarriedForward: Double
)

data class SetOffResult(
    val igst: HeadResult,
    val cgst: HeadResult,
    val sgst: HeadResult
) {
    /** Whole-rupee cash payable per head — CGST Act s.170, applied per levy. */
    val cashIgst: Double get() = Money.rupees(igst.payableInCash)
    val cashCgst: Double get() = Money.rupees(cgst.payableInCash)
    val cashSgst: Double get() = Money.rupees(sgst.payableInCash)
    val totalCash: Double get() = cashIgst + cashCgst + cashSgst
}

/**
 * Input tax credit set-off in the order the law requires.
 *
 * Rule 88A with s.49A and s.49B: **IGST credit must be exhausted first** — against IGST
 * liability, then in any order against CGST and SGST — before CGST or SGST credit may be
 * touched. And s.49(5) forbids CGST credit against SGST liability or the reverse.
 *
 * What this replaces netted each head independently and clamped at zero:
 *
 * ```
 * val netCgst = outCgst - inCgst          // 90,000 - 15,000 = 75,000
 * val netIgst = outIgst - inIgst          //      0 - 1,50,000 = -1,50,000
 * "…,${if (netCgst > 0) netCgst else 0.0},…"
 * ```
 *
 * For output CGST 90,000 / SGST 90,000 / IGST 0 against credit IGST 1,50,000 / CGST
 * 15,000 / SGST 15,000, that reported CGST ₹75,000, SGST ₹75,000 and a total of ₹0 —
 * four cells of one row contradicting each other by ₹1,50,000. The correct answer is ₹0
 * in cash: total liability 1,80,000 against total credit 1,80,000, and Rule 88A permits
 * an ordering that clears it exactly. The total cell happened to land on that ₹0, but only
 * by cancelling an unclamped -1,50,000 against the +1,50,000 the head cells had already
 * been clamped away from — it agreed with the right answer by accident while the two head
 * cells beside it demanded ₹1,50,000 in cash.
 *
 * (₹15,000 is the GREEDY figure — push all the IGST credit at CGST first, strand CGST's
 * own 15,000, pay 15,000 for SGST. Equally legal under Rule 88A and strictly worse.)
 *
 * The mechanism is worth naming because it is not a rounding problem: a set-off step has
 * **two** outputs per head — cash payable *and* credit carried forward. `coerceAtLeast(0)`
 * collapses them into one and discards the other, and because `max(0, x)` is not
 * invertible no totals check afterwards can detect what was lost. Here both outputs are
 * returned, and the invariants below hold exactly.
 */
object Rule88ASetOff {

    /**
     * @return per-head cash payable and credit carried forward, satisfying
     *   `creditUsed + creditCarriedForward == creditAvailable` for every head.
     *
     *   Note the OTHER invariant holds only for CGST and SGST: IGST's `creditUsed`
     *   counts credit spent on all three heads, so `creditUsed + payableInCash` exceeds
     *   IGST's own liability whenever spillover occurs. The KDoc previously claimed it
     *   universally, which is not true.
     */
    fun compute(
        outputIgst: Double,
        outputCgst: Double,
        outputSgst: Double,
        creditIgst: Double,
        creditCgst: Double,
        creditSgst: Double
    ): SetOffResult {
        val liabIgst = Money.paise(maxOf(0.0, outputIgst))
        val liabCgst = Money.paise(maxOf(0.0, outputCgst))
        val liabSgst = Money.paise(maxOf(0.0, outputSgst))

        var poolIgst = Money.paise(maxOf(0.0, creditIgst))
        val availIgst = poolIgst

        // 1. IGST credit against IGST liability first.
        val igstUsedOnIgst = minOf(poolIgst, liabIgst)
        poolIgst -= igstUsedOnIgst

        // 2. Remaining IGST credit spills over to CGST and SGST, and must be fully
        //    exhausted before either head's own credit may be used.
        //
        //    Rule 88A leaves the ORDER of that spill to the taxpayer, and the order
        //    matters: pushing greedily at CGST first strands CGST's own credit, which
        //    cannot be used against SGST (s.49(5)) and so sits in carry-forward while
        //    cash goes out for SGST. With output 90,000/90,000 and credit IGST 1,00,000 +
        //    CGST 50,000, greedy-CGST pays 80,000 where the optimum pays 30,000.
        //
        //    So the spill is directed where own credit CANNOT reach: each head is first
        //    covered by its own credit, and IGST fills what remains.
        val ownCgstAvailable = Money.paise(maxOf(0.0, creditCgst))
        val ownSgstAvailable = Money.paise(maxOf(0.0, creditSgst))

        val cgstShortfall = maxOf(0.0, liabCgst - ownCgstAvailable)
        val sgstShortfall = maxOf(0.0, liabSgst - ownSgstAvailable)

        var igstOnCgst = minOf(poolIgst, cgstShortfall)
        poolIgst -= igstOnCgst
        var igstOnSgst = minOf(poolIgst, sgstShortfall)
        poolIgst -= igstOnSgst

        // Any IGST credit still left must be used before own credit — it cannot be
        // carried forward while cash is being paid.
        val cgstStillDue = liabCgst - igstOnCgst
        val extraOnCgst = minOf(poolIgst, cgstStillDue)
        igstOnCgst += extraOnCgst
        poolIgst -= extraOnCgst

        val sgstStillDue = liabSgst - igstOnSgst
        val extraOnSgst = minOf(poolIgst, sgstStillDue)
        igstOnSgst += extraOnSgst
        poolIgst -= extraOnSgst

        // 3. Each head now covers what remains from its own credit, never the other's.
        val cgstRemaining = liabCgst - igstOnCgst
        val cgstOwnUsed = minOf(ownCgstAvailable, cgstRemaining)

        val sgstRemaining = liabSgst - igstOnSgst
        val sgstOwnUsed = minOf(ownSgstAvailable, sgstRemaining)

        val igstUsedOnCgst = igstOnCgst
        val igstUsedOnSgst = igstOnSgst

        return SetOffResult(
            igst = HeadResult(
                liability = liabIgst,
                creditAvailable = availIgst,
                creditUsed = igstUsedOnIgst + igstUsedOnCgst + igstUsedOnSgst,
                payableInCash = Money.paise(liabIgst - igstUsedOnIgst),
                // Whatever IGST credit survived all three steps.
                creditCarriedForward = Money.paise(poolIgst)
            ),
            cgst = HeadResult(
                liability = liabCgst,
                creditAvailable = ownCgstAvailable,
                creditUsed = cgstOwnUsed,
                payableInCash = Money.paise(cgstRemaining - cgstOwnUsed),
                creditCarriedForward = Money.paise(ownCgstAvailable - cgstOwnUsed)
            ),
            sgst = HeadResult(
                liability = liabSgst,
                creditAvailable = ownSgstAvailable,
                creditUsed = sgstOwnUsed,
                payableInCash = Money.paise(sgstRemaining - sgstOwnUsed),
                creditCarriedForward = Money.paise(ownSgstAvailable - sgstOwnUsed)
            )
        )
    }
}
