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

        val taxableValue = totalAmountInclusive / (1 + (gstRatePercentage / 100.0))
        val totalGstAmount = totalAmountInclusive - taxableValue

        val (cgstRate, sgstRate, igstRate) = if (isInterstate) {
            Triple(0.0, 0.0, gstRatePercentage)
        } else {
            Triple(gstRatePercentage / 2.0, gstRatePercentage / 2.0, 0.0)
        }

        val (cgstAmount, sgstAmount, igstAmount) = if (isInterstate) {
            Triple(0.0, 0.0, totalGstAmount)
        } else {
            Triple(totalGstAmount / 2.0, totalGstAmount / 2.0, 0.0)
        }

        return GstTaxBreakdown(
            grossAmount = totalAmountInclusive,
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
