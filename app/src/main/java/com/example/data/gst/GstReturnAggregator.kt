package com.example.data.gst

import com.example.data.model.VoucherEntity
import com.example.utils.GstCalculationService
import com.example.utils.Money

/**
 * Every monetary figure a GSTR row needs, taken from one posted voucher.
 *
 * Quantised, because every monetary field in the GSTR schema is capped at two decimals.
 * `isInterstate` is READ from the voucher and never recomputed: the posting is the
 * record and the return reports it.
 */
data class VoucherTax(
    val taxable: Double,
    val gst: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double,
    val rate: Double,
    val wasNegative: Boolean
)

/**
 * The GSTR-3B figures for one period, and the credit notes that belong in GSTR-1 9B.
 *
 * @param outwardClamped true when credit notes exceeded sales for the period. Table 3.1
 *   admits no negative value, so the excess must legally carry into the next period's
 *   return — which this app cannot do unaided. Reported rather than swallowed: a silent
 *   `max(0, x)` is not invertible and nothing downstream could detect the loss.
 */
data class ReturnTotals(
    val outwardTaxable: Double,
    val outwardCgst: Double,
    val outwardSgst: Double,
    val outwardIgst: Double,
    val outwardClamped: Boolean,
    val itcCgst: Double,
    val itcSgst: Double,
    val itcIgst: Double,
    val reversalCgst: Double,
    val reversalSgst: Double,
    val reversalIgst: Double
) {
    val hasReversal: Boolean get() = (reversalCgst + reversalSgst + reversalIgst) > 0.0
    val netItcCgst: Double get() = Money.paise(itcCgst - reversalCgst)
    val netItcSgst: Double get() = Money.paise(itcSgst - reversalSgst)
    val netItcIgst: Double get() = Money.paise(itcIgst - reversalIgst)
}

/**
 * The arithmetic of a GST return, as plain data in and plain data out.
 *
 * Extracted because [GstAutomationEngine] interleaves this with a Room open, Log,
 * telemetry, external storage and SharedPreferences, so none of it could be asserted on
 * the JVM — and none of it ever was. Same shape and same reason as [GstAuditEngine] and
 * `FinancialStatementEngine`.
 *
 * The defect this exists to prevent: credit notes are stored with POSITIVE amounts, and
 * the reversal is expressed by which legs get debited, not by the sign. So simply adding
 * SALES_RETURN to the export's voucher filter would have ADDED to the declared outward
 * liability — the original sale plus its reversal booked as a fresh supply — making the
 * business declare and pay tax on returned goods twice, in cash. Omitting credit notes
 * over-declares once; a sign-blind inclusion over-declares twice.
 */
object GstReturnAggregator {

    fun taxOf(voucher: VoucherEntity): VoucherTax {
        var taxable = GstCalculationService.taxableValueOf(voucher.totalAmount, voucher.gstAmount)
        var gst = Money.paise(voucher.gstAmount)
        val negative = taxable < 0.0 || gst < 0.0
        if (negative) {
            taxable = 0.0
            gst = 0.0
        }
        val (cgst, sgst, igst) = GstCalculationService.splitForVoucher(gst, voucher.isInterstate)
        return VoucherTax(
            taxable = taxable,
            gst = gst,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            rate = GstCalculationService.deriveGstRate(voucher.totalAmount, voucher.gstAmount),
            wasNegative = negative
        )
    }

    /**
     * GSTR-3B for one period.
     *
     * Table 3.1(a) is outward supply NET of credit notes issued. Table 4(A) stays gross
     * and purchase returns reverse into Table 4(B)(2) — they do NOT reduce 4(A), and they
     * produce no GSTR-1 row at all, because under GST it is the supplier who issues the
     * credit note for goods returned to them.
     */
    fun totalsFor(
        sales: List<VoucherEntity>,
        creditNotes: List<VoucherEntity>,
        purchases: List<VoucherEntity>,
        purchaseReturns: List<VoucherEntity>
    ): ReturnTotals {
        fun sum(list: List<VoucherEntity>, pick: (VoucherTax) -> Double) =
            Money.paise(list.sumOf { pick(taxOf(it)) })

        val netTaxable = Money.paise(sum(sales) { it.taxable } - sum(creditNotes) { it.taxable })
        val netCgst = Money.paise(sum(sales) { it.cgst } - sum(creditNotes) { it.cgst })
        val netSgst = Money.paise(sum(sales) { it.sgst } - sum(creditNotes) { it.sgst })
        val netIgst = Money.paise(sum(sales) { it.igst } - sum(creditNotes) { it.igst })

        return ReturnTotals(
            outwardTaxable = maxOf(0.0, netTaxable),
            outwardCgst = maxOf(0.0, netCgst),
            outwardSgst = maxOf(0.0, netSgst),
            outwardIgst = maxOf(0.0, netIgst),
            outwardClamped = netTaxable < 0.0 || netCgst < 0.0 || netSgst < 0.0 || netIgst < 0.0,
            itcCgst = sum(purchases) { it.cgst },
            itcSgst = sum(purchases) { it.sgst },
            itcIgst = sum(purchases) { it.igst },
            reversalCgst = sum(purchaseReturns) { it.cgst },
            reversalSgst = sum(purchaseReturns) { it.sgst },
            reversalIgst = sum(purchaseReturns) { it.igst }
        )
    }

    /**
     * Which GSTR-1 table a credit note belongs in.
     *
     * Routed through [GstClassifier] rather than a fresh predicate — that class exists
     * because three surfaces once each rolled their own and disagreed.
     *
     * Note the honest limitation: with no link from a note back to the invoice it
     * reverses, the B2CL threshold is measured against the NOTE's value, not the
     * original's. A partial credit note against a large B2C invoice therefore classifies
     * B2CS and nets into Table 7. That is the only classification this data supports.
     */
    enum class NoteTable { CDNR, CDNUR, NETS_INTO_B2CS }

    fun tableFor(partyGstin: String?, note: VoucherEntity): NoteTable =
        when (GstClassifier.classify(partyGstin, note.isInterstate, note.totalAmount)) {
            SupplyCategory.B2B -> NoteTable.CDNR
            SupplyCategory.B2CL -> NoteTable.CDNUR
            SupplyCategory.B2CS -> NoteTable.NETS_INTO_B2CS
        }
}
