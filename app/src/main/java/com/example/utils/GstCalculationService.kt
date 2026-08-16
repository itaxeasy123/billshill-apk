package com.example.utils

data class GstTaxBreakdown(
    val grossAmount: Double,
    val taxableValue: Double,
    val totalGstAmount: Double,
    val gstRatePercentage: Double,
    val isInterstate: Boolean,
    val cgstRate: Double,
    val sgstRate: Double,
    val igstRate: Double,
    val cgstAmount: Double,
    val sgstAmount: Double,
    val igstAmount: Double
)

object GstCalculationService {

    /**
     * Normalises a user-entered amount to the GST-inclusive gross value that the whole
     * posting pipeline downstream assumes.
     *
     * Everything below this point — [calculateGstBreakdown], the voucher's stored
     * totalAmount, the journal legs, and every GST return built from them — treats the
     * amount it receives as tax-inclusive. When the user enters an Exclusive (base)
     * amount, it has to be grossed up here, once, before it reaches any of them.
     *
     * Skipping this is what made a ₹10,000 + 18% sale post as ₹10,000 total instead of
     * ₹11,800: taxable ₹8,474.58 and ₹1,525.42 of GST, a 15.25% short-collection on the
     * app's own default entry path. Both the on-screen preview and the save handler now
     * call this, so what the user is shown and what gets posted cannot drift apart.
     */
    fun toGrossAmount(
        enteredAmount: Double,
        gstRatePercentage: Double,
        isGstInclusive: Boolean
    ): Double {
        if (isGstInclusive || gstRatePercentage <= 0.0 || enteredAmount <= 0.0) {
            return Money.paise(enteredAmount)
        }
        // Quantised here too. Returning the raw product meant createVoucher stored an
        // unrounded totalAmount and debited the party with it, while the sales and tax
        // legs came from the quantised breakdown — so an Exclusive entry of Rs 1,234.56
        // at 18% posted Dr 1456.7807999999998 against Cr 1456.78 and left a sub-paisa
        // imbalance on every voucher, which accumulates into the "your books do not
        // balance" banner and strands an unsettleable residue on the party's ledger.
        return Money.paise(enteredAmount * (1.0 + (gstRatePercentage / 100.0)))
    }

    /**
     * The GST slabs an Indian invoice can actually carry, including the 40% de-merit rate.
     * Public so entry paths can reject anything else BEFORE it reaches the ledger.
     */
    val SLABS = listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0)

    /**
     * Whether a rate corresponds to a real slab.
     *
     * [deriveGstRate] deliberately returns off-slab rates unsnapped so they "show up as
     * the anomaly it is" — this is the predicate that finally makes something show them.
     */
    fun isLegalSlab(rate: Double): Boolean =
        SLABS.any { kotlin.math.abs(it - rate) <= 0.05 }

    /**
     * Recovers the rate a posted voucher was charged at, from its own stored figures.
     *
     * The edit dialogs used to seed `if (gstAmount > 0) "18" else "0"`, so opening any
     * 5%, 12% or 28% invoice and saving silently rewrote it to 18%. Deriving the rate
     * and snapping it to the nearest real slab recovers the true value: the arithmetic
     * is exact by construction, and the snap absorbs stored-Double noise.
     */
    fun deriveGstRate(totalAmountInclusive: Double, gstAmount: Double): Double {
        val taxable = totalAmountInclusive - gstAmount
        if (gstAmount <= 0.0 || taxable <= 0.0) return 0.0
        val raw = (gstAmount / taxable) * 100.0
        // Snap only when the derived rate is genuinely one of the slabs, allowing for
        // stored rounding. A voucher whose amounts imply 9.6% is NOT a 12% voucher, and
        // reporting it as one would put a rate on a GST return that contradicts the tax
        // amounts printed beside it. Anything that far off is returned as-is so it shows
        // up as the anomaly it is.
        val nearest = SLABS.minByOrNull { kotlin.math.abs(it - raw) } ?: raw
        return if (kotlin.math.abs(nearest - raw) <= 0.05) nearest else raw
    }

    /**
     * Calculates tax components from total inclusive amount or taxable amount based on interstate state of supply.
     */
    fun calculateGstBreakdown(
        totalAmountInclusive: Double,
        gstRatePercentage: Double,
        isInterstate: Boolean
    ): GstTaxBreakdown {
        if (gstRatePercentage <= 0.0 || totalAmountInclusive <= 0.0) {
            return GstTaxBreakdown(
                grossAmount = totalAmountInclusive,
                taxableValue = totalAmountInclusive,
                totalGstAmount = 0.0,
                gstRatePercentage = 0.0,
                isInterstate = isInterstate,
                cgstRate = 0.0,
                sgstRate = 0.0,
                igstRate = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                igstAmount = 0.0
            )
        }

        // Every component is quantised to paise, and the halves are derived from the
        // total by residual before quantising, so CGST and SGST reconcile to the GST
        // amount. Rounding each half independently from the raw value is what let them
        // disagree with the total by a paisa on odd-paise bases (a Rs 1,000.05 base at
        // 18% gave halves summing to 180.00 against a total of 180.01), with the missing
        // paisa going nowhere.
        //
        // This also stops raw Doubles like 762.7118644067797 reaching the GSTR JSON,
        // where every monetary field is capped at two decimal places and anything longer
        // is rejected by the portal.
        //
        // Quantising every component leaves ~1e-13 of binary addition noise in
        // `taxable + cgst + sgst` vs `gross`. That is deliberate and safe: the
        // journal-imbalance banner triggers at Rs 0.01, eleven orders of magnitude
        // above it, and 2dp values are what the statutory schema actually validates.
        val grossAmount = Money.paise(totalAmountInclusive)
        val totalGstAmount = Money.paise(grossAmount - grossAmount / (1 + (gstRatePercentage / 100.0)))
        val taxableValue = Money.paise(grossAmount - totalGstAmount)

        val (cgstRate, sgstRate, igstRate) = if (isInterstate) {
            Triple(0.0, 0.0, gstRatePercentage)
        } else {
            Triple(gstRatePercentage / 2.0, gstRatePercentage / 2.0, 0.0)
        }

        val (cgstAmount, sgstAmount, igstAmount) = splitHeads(totalGstAmount, isInterstate)

        return GstTaxBreakdown(
            grossAmount = grossAmount,
            taxableValue = taxableValue,
            totalGstAmount = totalGstAmount,
            gstRatePercentage = gstRatePercentage,
            isInterstate = isInterstate,
            cgstRate = cgstRate,
            sgstRate = sgstRate,
            igstRate = igstRate,
            cgstAmount = cgstAmount,
            sgstAmount = sgstAmount,
            igstAmount = igstAmount
        )
    }

    /**
     * The one and only way a GST total is divided into heads.
     *
     * Intrastate: CGST is the quantised half and takes the odd paisa; SGST is the
     * **residual**, so the two always add back to [totalGstAmount] exactly. Rounding each
     * half independently instead — `total / 2.0` twice — is what let a Rs 1,000.05 base at
     * 18% print halves of 90.005 each: three decimals into fields the GSTR schema caps at
     * two, and a printed invoice whose CGST + SGST exceeded its own tax total by a paisa.
     *
     * Interstate: the whole tax is IGST and the state heads are zero.
     *
     * Every display and export site derives its split from here rather than halving a
     * total itself. Because a posted voucher's `gstAmount` IS the `totalGstAmount` this
     * was computed from, [splitForVoucher] reproduces what was stored at posting time —
     * and repairs vouchers posted before this convention existed, whose stored rows still
     * hold the old unquantised halves.
     */
    fun splitHeads(totalGstAmount: Double, isInterstate: Boolean): Triple<Double, Double, Double> =
        if (isInterstate) {
            Triple(0.0, 0.0, Money.paise(totalGstAmount))
        } else {
            val cgst = Money.paise(totalGstAmount / 2.0)
            Triple(cgst, Money.paise(totalGstAmount - cgst), 0.0)
        }

    /**
     * The CGST / SGST / IGST of an already-posted voucher, in that order.
     *
     * Reads nothing from the database on purpose: the split is a pure function of the
     * voucher's own `gstAmount` and `isInterstate`, both of which sit on the row being
     * rendered. That keeps every renderer synchronous — no suspend call, no frame where
     * a tax invoice paints with its tax missing, and no main-thread database read.
     */
    fun splitForVoucher(gstAmount: Double, isInterstate: Boolean): Triple<Double, Double, Double> =
        splitHeads(gstAmount, isInterstate)

    /**
     * The taxable value of a posted voucher, quantised.
     *
     * `totalAmount - gstAmount` as a raw Double put values like 762.7118644067797 into
     * `txval`, which the portal rejects for the same reason as the split.
     */
    fun taxableValueOf(totalAmount: Double, gstAmount: Double): Double =
        Money.paise(Money.paise(totalAmount) - Money.paise(gstAmount))

    fun isInterstateSupply(supplierState: String, recipientState: String): Boolean {
        if (supplierState.isBlank() || recipientState.isBlank()) return false
        return !supplierState.trim().equals(recipientState.trim(), ignoreCase = true)
    }
}
