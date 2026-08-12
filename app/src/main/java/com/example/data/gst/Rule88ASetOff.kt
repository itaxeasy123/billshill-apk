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
 * four cells of one row contradicting each other by ₹1,50,000, with the correct answer
 * (₹15,000) appearing nowhere.
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
     *   `creditUsed + creditCarriedForward == creditAvailable` and
     *   `creditUsed + payableInCash == liability` for every head.
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

        // 2. Remaining IGST credit spills over to CGST and SGST. It MUST be fully
        //    exhausted before either of their own credits may be used.
        val igstUsedOnCgst = minOf(poolIgst, liabCgst)
        poolIgst -= igstUsedOnCgst
        val igstUsedOnSgst = minOf(poolIgst, liabSgst - 0.0)
        poolIgst -= igstUsedOnSgst

        // 3. Only now may each head use its own credit — and never the other's (s.49(5)).
        val cgstRemaining = liabCgst - igstUsedOnCgst
        val cgstOwnUsed = minOf(Money.paise(maxOf(0.0, creditCgst)), cgstRemaining)

        val sgstRemaining = liabSgst - igstUsedOnSgst
        val sgstOwnUsed = minOf(Money.paise(maxOf(0.0, creditSgst)), sgstRemaining)

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
                creditAvailable = Money.paise(maxOf(0.0, creditCgst)),
                creditUsed = cgstOwnUsed,
                payableInCash = Money.paise(cgstRemaining - cgstOwnUsed),
                creditCarriedForward = Money.paise(maxOf(0.0, creditCgst) - cgstOwnUsed)
            ),
            sgst = HeadResult(
                liability = liabSgst,
                creditAvailable = Money.paise(maxOf(0.0, creditSgst)),
                creditUsed = sgstOwnUsed,
                payableInCash = Money.paise(sgstRemaining - sgstOwnUsed),
                creditCarriedForward = Money.paise(maxOf(0.0, creditSgst) - sgstOwnUsed)
            )
        )
    }
}
