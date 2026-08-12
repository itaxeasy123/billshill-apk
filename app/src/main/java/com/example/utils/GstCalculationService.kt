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
        if (isGstInclusive || gstRatePercentage <= 0.0 || enteredAmount <= 0.0) return enteredAmount
        return enteredAmount * (1.0 + (gstRatePercentage / 100.0))
    }

    /** GST slabs an Indian invoice can actually carry, including the 40% de-merit rate. */
    private val SLABS = listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0)

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
        return SLABS.minByOrNull { kotlin.math.abs(it - raw) } ?: raw
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

        val (cgstAmount, sgstAmount, igstAmount) = if (isInterstate) {
            Triple(0.0, 0.0, totalGstAmount)
        } else {
            val cgst = Money.paise(totalGstAmount / 2.0)
            Triple(cgst, Money.paise(totalGstAmount - cgst), 0.0)
        }

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
     * Determines whether state codes or state names indicate inter-state supply in India.
     */
    fun isInterstateSupply(supplierState: String, recipientState: String): Boolean {
        if (supplierState.isBlank() || recipientState.isBlank()) return false
        return !supplierState.trim().equals(recipientState.trim(), ignoreCase = true)
    }
}
