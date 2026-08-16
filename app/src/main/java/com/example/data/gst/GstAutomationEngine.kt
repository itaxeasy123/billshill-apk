package com.example.data.gst

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.ai.LocalAiReconciliationEngine
import com.example.data.db.AppDatabase
import com.example.utils.GstCalculationService
import com.example.utils.IndianFormatter
import com.example.utils.FiscalYearUtils
import com.example.utils.Money
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.utils.TelemetryEngine
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Automated GST Data Mapping & Tax Splitting Engine.
 * Aggregates Room DB transactions into government-compliant GSTR-1 and GSTR-3B JSON payloads,
 * applies automated Intrastate (CGST/SGST 50-50) vs Interstate (IGST 100%) tax rules,
 * enforces negative-value guardrails, and exports finalized JSON files to local storage.
 */
object GstAutomationEngine {

    private const val TAG = "GstAutomationEngine"
    private const val PREFS_NAME = "gst_export_preferences"
    private const val KEY_LAST_EXPORT_STATUS = "last_automated_gst_export"

    private data class TaxSplit(
        val cgst: Double,
        val sgst: Double,
        val igst: Double
    )

    /**
     * Executes full background aggregation, tax splitting, GSTR-1 / GSTR-3B generation,
     * file serialization, and SharedPreferences status tracking.
     */
    suspend fun executeAutomatedGstExport(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting automated GST data mapping and export routine...")
            val db = AppDatabase.getDatabase(context)
            val accountingDao = db.accountingDao()

            // 1. Fetch Business Profile.
            // This block used to manufacture an entire business identity when the profile
            // was missing -- "Apex Enterprises India", phone +919876543210, and GSTIN
            // 07AAAAA1234A1Z5 -- and a second fallback substituted that same GSTIN when the
            // real one was empty. Both wrote a GSTIN belonging to nobody into a payload
            // intended for a government return. An export with no verified GSTIN is not a
            // valid return, so it now fails loudly instead of filing under a made-up number.
            val userProfile = accountingDao.getUserSync()
                ?: throw IllegalStateException(
                    "GST export aborted: no business profile saved. Add your business details and GSTIN in Settings before exporting."
                )

            val clientGstin = userProfile.gstin.trim()
            if (clientGstin.isEmpty()) {
                throw IllegalStateException(
                    "GST export aborted: your GSTIN is not set. Add your GSTIN in Settings before exporting a GSTR-1 / GSTR-3B payload."
                )
            }

            // Extract 2-digit Profile State Code (e.g. "07" from "07AAAAA1234A1Z5")
            val clientStateCode = if (clientGstin.length >= 2 && clientGstin.substring(0, 2).all { it.isDigit() }) {
                clientGstin.substring(0, 2)
            } else {
                // No longer silently defaults to Delhi -- an unrecognised state name is
                // reported so the user can correct it rather than being filed as "07".
                getStateCodeFromStateName(userProfile.state)
                    ?: throw IllegalStateException(
                        "GST export aborted: could not determine your state code. GSTIN '$clientGstin' has no numeric state prefix and the saved state " +
                            (if (userProfile.state.isBlank()) "is empty." else "'${userProfile.state}' is not a recognised Indian state.")
                    )
            }

            val currentPeriod = SimpleDateFormat("MMyyyy", Locale.US).format(Date())

            // 2. Vouchers for THIS tax period only.
            //
            // The period was stamped as the current month while every voucher ever posted
            // was included, so month 2 filed month 1 all over again and year 3 filed the
            // entire book — under a heading claiming one month. A GST return covers
            // exactly one period.
            val periodBounds = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val periodStart = periodBounds.timeInMillis
            val periodEnd = periodBounds.apply { add(Calendar.MONTH, 1) }.timeInMillis - 1

            val everyVoucherInBook = accountingDao.getAllVouchersSync()
            val allVouchers = everyVoucherInBook
                .filter { it.date in periodStart..periodEnd }
                // Oldest first, so the document series in docIssue runs forwards. The DAO
                // returns newest-first, which made `from` the newest invoice and `to` the
                // oldest — a declared range running backwards.
                .sortedBy { it.date }
            val salesVouchers = allVouchers.filter { it.voucherType == VoucherType.SALES }
            val purchaseVouchers = allVouchers.filter { it.voucherType == VoucherType.PURCHASE }
            // Neither of these was ever bound to a variable, so credit notes reached no
            // GSTR-1 table and purchase returns reversed no ITC. The books net both
            // (getGstSummaryFlow) — only the filed return did not.
            //
            // They are kept in SEPARATE lists rather than merged into the two above: a
            // credit note is stored with POSITIVE amounts, with the reversal carried by
            // which legs are debited. Merging would have added them to outward liability
            // instead of subtracting, declaring the tax on returned goods twice.
            val creditNoteVouchers = allVouchers.filter { it.voucherType == VoucherType.SALES_RETURN }
            val purchaseReturnVouchers = allVouchers.filter { it.voucherType == VoucherType.PURCHASE_RETURN }

            Log.d(TAG, "Found ${salesVouchers.size} sales vouchers and ${purchaseVouchers.size} purchase vouchers.")

            // --- GSTR-1 AGGREGATION ---
            val b2bMap = mutableMapOf<String, MutableList<Gstr1Invoice>>()
            val b2csAccumulator = mutableMapOf<Triple<String, String, Double>, Gstr1B2csItem>()
            val b2clMap = mutableMapOf<String, MutableList<Gstr1B2clInvoice>>()
            val hsnMap = mutableMapOf<String, Gstr1HsnDetail>()

            var totalSalesTaxable = 0.0
            var totalSalesCgst = 0.0
            var totalSalesSgst = 0.0
            var totalSalesIgst = 0.0

            var invoiceSeqStart = ""
            var invoiceSeqEnd = ""
            var salesInvoiceCount = 0
            var hsnUnclassifiedCount = 0

            salesVouchers.forEachIndexed { index, voucher ->
                val partyLedger = accountingDao.getLedgerByName(voucher.partyName)
                val partyGstin = partyLedger?.gstin?.trim() ?: ""

                // POS Determination
                val posStateCode = if (partyGstin.length >= 2 && partyGstin.substring(0, 2).all { it.isDigit() }) {
                    partyGstin.substring(0, 2)
                } else if (partyLedger?.state?.isNotBlank() == true) {
                    // An unrecognised state name used to resolve to "07" (Delhi), which
                    // silently changes the place of supply and therefore the CGST/SGST vs
                    // IGST split on a filed return. It is now reported instead of guessed.
                    getStateCodeFromStateName(partyLedger.state)
                        ?: throw IllegalStateException(
                            "GST export aborted: party '${voucher.partyName}' (invoice #${voucher.voucherNo}) has an unrecognised state '${partyLedger.state}'. " +
                                "Correct the state on that ledger before exporting."
                        )
                } else {
                    clientStateCode // Default to Intrastate if unassigned
                }

                // Read from the voucher, not recomputed. Deriving it from state codes let
                // the return disagree with the books: a sale posted interstate (credited
                // to IGST) whose party has no GSTIN and no state resolved to the seller's
                // own state, so it was exported as CGST+SGST while the ledgers said IGST.
                // The posting is the record; the return reports it.
                val isInterstate = voucher.isInterstate

                // Quantised, because every monetary field in the GSTR schema is capped at
                // two decimals and the portal rejects anything longer. A raw subtraction
                // put values like 762.7118644067797 into txval.
                var taxableVal = GstCalculationService.taxableValueOf(voucher.totalAmount, voucher.gstAmount)
                var totalGst = Money.paise(voucher.gstAmount)

                // Guardrail: Normalize negative values to 0.0 & log telemetry
                if (taxableVal < 0 || totalGst < 0) {
                    val errorNote = "Negative value detected in Sales Voucher #${voucher.voucherNo}: val=$taxableVal, gst=$totalGst. Normalizing to 0.0."
                    Log.w(TAG, errorNote)
                    TelemetryEngine.recordThrowable(context, IllegalArgumentException(errorNote), "GST_EXPORT_GUARDRAIL")
                    taxableVal = normalizeToZero(taxableVal)
                    totalGst = normalizeToZero(totalGst)
                }

                // Was totalGst / 2.0 twice: an 180.01 tax emitted camt 90.005 and samt
                // 90.005 — three decimals into a two-decimal field, rejected by the portal,
                // and disagreeing with the invoice issued to the customer. The shared split
                // is the one the ledger legs and the printed invoice both use.
                val (cgstShare, sgstShare, igstShare) =
                    GstCalculationService.splitForVoucher(totalGst, isInterstate)
                val split = TaxSplit(cgst = cgstShare, sgst = sgstShare, igst = igstShare)

                totalSalesTaxable += taxableVal
                totalSalesCgst += split.cgst
                totalSalesSgst += split.sgst
                totalSalesIgst += split.igst

                salesInvoiceCount++
                val sanitizedInum = LocalAiReconciliationEngine.sanitizeInvoiceNumber(voucher.voucherNo)
                if (index == 0) invoiceSeqStart = sanitizedInum
                invoiceSeqEnd = sanitizedInum

                val dateStr = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(voucher.date))

                // The rate the voucher was actually charged at. This was a blanket 18.0
                // on every line, so a 5% or 28% invoice was reported to the government at
                // the wrong rate — and the tax amounts beside it then contradicted it.
                val voucherRate = GstCalculationService.deriveGstRate(voucher.totalAmount, voucher.gstAmount)

                val invItem = Gstr1InvoiceItem(
                    num = 1,
                    itmDet = Gstr1ItmDet(
                        rt = voucherRate,
                        txval = taxableVal,
                        iamt = split.igst,
                        camt = split.cgst,
                        samt = split.sgst,
                        csamt = 0.0
                    )
                )

                // The shared classifier, so the JSON agrees with the screen and the CSV.
                // This branch used to be a hand-rolled `partyGstin.length >= 15` with no
                // B2CL case at all, so an inter-state consumer invoice above Rs 1 lakh
                // was aggregated into Table 7 in the file actually filed (H25).
                val category = GstClassifier.classify(partyGstin, isInterstate, voucher.totalAmount)

                if (category == SupplyCategory.B2B) {
                    // B2B Customer with GSTIN
                    val b2bInv = Gstr1Invoice(
                        inum = sanitizedInum,
                        idt = dateStr,
                        valAmt = voucher.totalAmount,
                        pos = posStateCode,
                        rchrg = "N",
                        invType = "R",
                        itms = listOf(invItem)
                    )
                    b2bMap.getOrPut(partyGstin) { mutableListOf() }.add(b2bInv)
                } else if (category == SupplyCategory.B2CL) {
                    b2clMap.getOrPut(posStateCode) { mutableListOf() }.add(
                        Gstr1B2clInvoice(
                            inum = sanitizedInum,
                            idt = dateStr,
                            valAmt = voucher.totalAmount,
                            itms = listOf(invItem)
                        )
                    )
                } else {
                    // B2CS Consumer
                    // Table 7 is an AGGREGATE by place of supply, rate and supply type —
                    // one row per consumer sale is not the shape the schema expects.
                    val key = Triple(if (isInterstate) "INTER" else "INTRA", posStateCode, voucherRate)
                    val prev = b2csAccumulator[key]
                    b2csAccumulator[key] = if (prev == null) {
                        Gstr1B2csItem(
                            splyTy = key.first, pos = key.second, rt = key.third,
                            txval = taxableVal, iamt = split.igst,
                            camt = split.cgst, samt = split.sgst, csamt = 0.0
                        )
                    } else {
                        prev.copy(
                            txval = prev.txval + taxableVal,
                            iamt = prev.iamt + split.igst,
                            camt = prev.camt + split.cgst,
                            samt = prev.samt + split.sgst
                        )
                    }
                }

                // HSN Summary Rollup — from the item actually sold.
                // Was hardcoded "9983" (a SAC for professional services) with the
                // description "General Commercial Supplies", so every sale of every kind
                // was reported under one classification the business had never chosen.
                // Table 12 is only meaningful for invoices carrying line items; vouchers
                // without them are counted and reported instead of being invented.
                val lineItems = accountingDao.getVoucherItemsForVoucher(voucher.id)
                val itemMaster = lineItems.firstOrNull()?.let { accountingDao.getInventoryItemById(it.itemId) }
                val hsnCode = itemMaster?.hsnCode?.trim().orEmpty()
                if (itemMaster == null || hsnCode.isEmpty()) {
                    hsnUnclassifiedCount++
                    return@forEachIndexed
                }
                val hsnQty = lineItems.sumOf { it.quantity }
                val hsnUqc = toUqc(itemMaster.unit)
                val existingHsn = hsnMap[hsnCode]
                if (existingHsn != null) {
                    hsnMap[hsnCode] = existingHsn.copy(
                        qty = existingHsn.qty + hsnQty,
                        totalVal = existingHsn.totalVal + voucher.totalAmount,
                        txval = existingHsn.txval + taxableVal,
                        iamt = existingHsn.iamt + split.igst,
                        camt = existingHsn.camt + split.cgst,
                        samt = existingHsn.samt + split.sgst
                    )
                } else {
                    hsnMap[hsnCode] = Gstr1HsnDetail(
                        num = hsnMap.size + 1,
                        hsnSc = hsnCode,
                        desc = itemMaster.name,
                        uqc = hsnUqc,
                        qty = hsnQty,
                        totalVal = voucher.totalAmount,
                        txval = taxableVal,
                        iamt = split.igst,
                        camt = split.cgst,
                        samt = split.sgst,
                        csamt = 0.0
                    )
                }
            }

            // --- GSTR-1 Table 9B: credit notes ---
            //
            // GSTR-1 and GSTR-3B treat a credit note in OPPOSITE ways, which is why this
            // is a separate loop rather than a sign flip in the one above. In GSTR-1 the
            // note is its own record in 9B and Table 4A stays GROSS; in GSTR-3B it is a
            // net reduction of 3.1(a). One set of accumulators cannot serve both.
            //
            // Values here are POSITIVE and ntty="C" carries the direction — the portal
            // subtracts. That is also how this app stores a return.
            val cdnrMap = mutableMapOf<String, MutableList<Gstr1CreditNote>>()
            val cdnurNotes = mutableListOf<Gstr1CdnurNote>()
            var creditNotesNettedIntoB2cs = 0

            creditNoteVouchers.forEach { voucher ->
                val partyLedger = accountingDao.getLedgerByName(voucher.partyName)
                val partyGstin = partyLedger?.gstin?.trim() ?: ""

                val posStateCode = if (partyGstin.length >= 2 && partyGstin.substring(0, 2).all { it.isDigit() }) {
                    partyGstin.substring(0, 2)
                } else if (partyLedger?.state?.isNotBlank() == true) {
                    getStateCodeFromStateName(partyLedger.state)
                        ?: throw IllegalStateException(
                            "GST export aborted: party '${voucher.partyName}' (credit note #${voucher.voucherNo}) has an unrecognised state '${partyLedger.state}'. " +
                                "Correct the state on that ledger before exporting."
                        )
                } else {
                    clientStateCode
                }

                val tax = GstReturnAggregator.taxOf(voucher)
                if (tax.wasNegative) {
                    val note = "Negative value in Credit Note #${voucher.voucherNo}. Normalizing."
                    Log.w(TAG, note)
                    TelemetryEngine.recordThrowable(context, IllegalArgumentException(note), "GST_EXPORT_GUARDRAIL")
                }

                val items = listOf(
                    Gstr1InvoiceItem(
                        num = 1,
                        itmDet = Gstr1ItmDet(
                            rt = tax.rate,
                            txval = tax.taxable,
                            iamt = tax.igst,
                            camt = tax.cgst,
                            samt = tax.sgst,
                            csamt = 0.0
                        )
                    )
                )
                val noteNo = LocalAiReconciliationEngine.sanitizeInvoiceNumber(voucher.voucherNo)
                val noteDate = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(voucher.date))

                // Routed through the shared classifier. A note to an unregistered party
                // must never reach CDNR: it would emit `"ctin": ""` and the portal rejects
                // the whole upload. CDNUR's typ enum has no B2CS member, so a small B2C
                // note has no note table at all and nets into Table 7 instead.
                when (GstReturnAggregator.tableFor(partyGstin, voucher)) {
                    GstReturnAggregator.NoteTable.CDNR ->
                        cdnrMap.getOrPut(partyGstin) { mutableListOf() }.add(
                            Gstr1CreditNote(
                                ntNum = noteNo,
                                ntDt = noteDate,
                                pos = posStateCode,
                                valAmt = Money.paise(voucher.totalAmount),
                                itms = items
                            )
                        )

                    GstReturnAggregator.NoteTable.CDNUR ->
                        cdnurNotes.add(
                            Gstr1CdnurNote(
                                ntNum = noteNo,
                                ntDt = noteDate,
                                pos = posStateCode,
                                valAmt = Money.paise(voucher.totalAmount),
                                itms = items
                            )
                        )

                    GstReturnAggregator.NoteTable.NETS_INTO_B2CS -> {
                        val supplyType = if (voucher.isInterstate) "INTER" else "INTRA"
                        val key = Triple(supplyType, posStateCode, tax.rate)
                        val prev = b2csAccumulator[key]
                        // Subtracted, not added. Clamped at zero and reported: Table 7 is
                        // rejected on a negative txval, and silently dropping the excess
                        // would destroy a carry-forward nothing could later detect.
                        if (prev != null) {
                            b2csAccumulator[key] = prev.copy(
                                txval = maxOf(0.0, Money.paise(prev.txval - tax.taxable)),
                                camt = maxOf(0.0, Money.paise(prev.camt - tax.cgst)),
                                samt = maxOf(0.0, Money.paise(prev.samt - tax.sgst)),
                                iamt = maxOf(0.0, Money.paise(prev.iamt - tax.igst))
                            )
                        }
                        creditNotesNettedIntoB2cs++
                    }
                }
            }

            val b2bGroups = b2bMap.map { (ctin, invList) -> Gstr1B2bGroup(ctin, invList) }

            // Document-issued summary. When there are no sales invoices in the period this
            // used to emit a made-up invoice range "INV-0001" to "INV-0001" against a count
            // of zero -- a document series the user never issued. An empty period now
            // reports an empty series.
            val docSummary = Gstr1DocSummary(
                docDet = if (salesInvoiceCount > 0) {
                    listOf(
                        Gstr1DocDetail(
                            docNum = 1,
                            docTyp = "Invoices for outward supply",
                            from = invoiceSeqStart,
                            to = invoiceSeqEnd,
                            totcnt = salesInvoiceCount,
                            cancel = 0,
                            netIssue = salesInvoiceCount
                        )
                    )
                } else {
                    emptyList()
                } + if (creditNoteVouchers.isNotEmpty()) {
                    // Credit notes run their own series (SRN/...), so merging them into the
                    // invoice range would declare one range spanning INV.. to SRN.. with an
                    // inflated count. Table 13 enumerates them separately.
                    listOf(
                        Gstr1DocDetail(
                            docNum = 5,
                            docTyp = "Credit Note",
                            from = LocalAiReconciliationEngine.sanitizeInvoiceNumber(creditNoteVouchers.first().voucherNo),
                            to = LocalAiReconciliationEngine.sanitizeInvoiceNumber(creditNoteVouchers.last().voucherNo),
                            totcnt = creditNoteVouchers.size,
                            cancel = 0,
                            netIssue = creditNoteVouchers.size
                        )
                    )
                } else {
                    emptyList()
                }
            )

            // `cur_gt` is gross turnover for the CURRENT FY up to and including this period
            // — April-to-date, cumulative. It was assigned the single month's taxable value,
            // so a December return declared December's turnover as the year's. This window
            // the book does cover, so it is computed.
            val currentFyStart = FiscalYearUtils.fyBounds(FiscalYearUtils.fyStartYearFor(periodEnd)).first
            val curGtToDate = Money.paise(
                everyVoucherInBook
                    .filter { it.voucherType == VoucherType.SALES && it.date in currentFyStart..periodEnd }
                    .sumOf { GstCalculationService.taxableValueOf(it.totalAmount, it.gstAmount) }
                    - everyVoucherInBook
                        .filter { it.voucherType == VoucherType.SALES_RETURN && it.date in currentFyStart..periodEnd }
                        .sumOf { GstCalculationService.taxableValueOf(it.totalAmount, it.gstAmount) }
            )

            // `gt` is the PRECEDING financial year's aggregate turnover. It was assigned this
            // period's taxable value — the same number as cur_gt, which is how the error is
            // visible in the payload: two fields with different definitions carrying an
            // identical figure.
            //
            // NOT derived. s.2(6) aggregate turnover is PAN-level and includes exempt, export
            // and non-GST supplies this book does not distinguish, and the book may not span
            // the previous FY at all. A derived zero would be a DECLARATION, not an omission,
            // so the export stops and asks — the same guard this engine already applies to a
            // missing GSTIN.
            val gtDeclared = userProfile.previousFyAggregateTurnover
            if (gtDeclared < 0.0) {
                val prevFyStartYear = FiscalYearUtils.fyStartYearFor(periodEnd) - 1
                val prevBounds = FiscalYearUtils.fyBounds(prevFyStartYear)
                val earliestVoucher = everyVoucherInBook.minOfOrNull { it.date }
                val bookCoversPreviousFy = earliestVoucher != null && earliestVoucher <= prevBounds.first
                val hint = if (bookCoversPreviousFy) {
                    val recorded = Money.paise(
                        everyVoucherInBook
                            .filter { it.voucherType == VoucherType.SALES && it.date in prevBounds.first..prevBounds.second }
                            .sumOf { GstCalculationService.taxableValueOf(it.totalAmount, it.gstAmount) }
                    )
                    " This book records ${IndianFormatter.formatRupee(recorded)} of taxable outward supplies for that year, " +
                        "which is a starting point but is not aggregate turnover: that figure is PAN-level across all your " +
                        "GSTINs and includes exempt, nil-rated and export supplies."
                } else {
                    " This book does not cover that year, so the figure cannot be read from your data."
                }
                throw IllegalStateException(
                    "GST export aborted: the previous financial year's aggregate turnover is not set. " +
                        "Enter it under Settings before exporting a GSTR-1 payload.$hint"
                )
            }

            val gstr1Payload = Gstr1Payload(
                gstin = clientGstin,
                fp = currentPeriod,
                gt = Money.paise(gtDeclared),
                curGt = curGtToDate,
                b2b = b2bGroups,
                b2cl = b2clMap.map { (pos, invoices) -> Gstr1B2clGroup(pos = pos, inv = invoices) },
                b2cs = b2csAccumulator.values.toList(),
                cdnr = cdnrMap.map { (ctin, notes) -> Gstr1CdnrGroup(ctin, notes) },
                cdnur = cdnurNotes,
                hsn = Gstr1HsnData(data = hsnMap.values.toList()),
                docIssue = docSummary
            )

            // --- GSTR-3B AGGREGATION ---
            var totalPurchaseTaxable = 0.0
            var totalPurchaseCgst = 0.0
            var totalPurchaseSgst = 0.0
            var totalPurchaseIgst = 0.0

            purchaseVouchers.forEach { voucher ->
                val partyLedger = accountingDao.getLedgerByName(voucher.partyName)
                val partyGstin = partyLedger?.gstin?.trim() ?: ""

                val posStateCode = if (partyGstin.length >= 2 && partyGstin.substring(0, 2).all { it.isDigit() }) {
                    partyGstin.substring(0, 2)
                } else {
                    clientStateCode
                }

                // Read from the voucher, not recomputed. Deriving it from state codes let
                // the return disagree with the books: a sale posted interstate (credited
                // to IGST) whose party has no GSTIN and no state resolved to the seller's
                // own state, so it was exported as CGST+SGST while the ledgers said IGST.
                // The posting is the record; the return reports it.
                val isInterstate = voucher.isInterstate

                var taxableVal = GstCalculationService.taxableValueOf(voucher.totalAmount, voucher.gstAmount)
                var totalGst = Money.paise(voucher.gstAmount)

                if (taxableVal < 0 || totalGst < 0) {
                    val errorNote = "Negative value in Purchase Voucher #${voucher.voucherNo}: val=$taxableVal, gst=$totalGst. Normalizing."
                    Log.w(TAG, errorNote)
                    TelemetryEngine.recordThrowable(context, IllegalArgumentException(errorNote), "GST_EXPORT_GUARDRAIL")
                    taxableVal = normalizeToZero(taxableVal)
                    totalGst = normalizeToZero(totalGst)
                }

                val (cgstShare, sgstShare, igstShare) =
                    GstCalculationService.splitForVoucher(totalGst, isInterstate)
                val split = TaxSplit(cgst = cgstShare, sgst = sgstShare, igst = igstShare)

                totalPurchaseTaxable += taxableVal
                totalPurchaseCgst += split.cgst
                totalPurchaseSgst += split.sgst
                totalPurchaseIgst += split.igst
            }

            // Table 3.1(a) is outward supply NET of the credit notes issued, and
            // Table 4(B)(2) reverses the ITC on goods returned to a vendor. Both computed
            // by the pure aggregator so they can be asserted on the JVM — nothing has ever
            // tested this engine.
            val returnTotals = GstReturnAggregator.totalsFor(
                sales = salesVouchers,
                creditNotes = creditNoteVouchers,
                purchases = purchaseVouchers,
                purchaseReturns = purchaseReturnVouchers
            )

            val supDetails = Gstr3bSupDetails(
                osupDet = Gstr3bTaxTuple(
                    txval = returnTotals.outwardTaxable,
                    iamt = returnTotals.outwardIgst,
                    camt = returnTotals.outwardCgst,
                    samt = returnTotals.outwardSgst,
                    csamt = 0.0
                )
            )

            val itcItem = Gstr3bItcItem(
                // "OTH" is the value the GSTR-3B schema defines for "All other ITC";
                // the enum accepts only IMPG / IMPS / ISRC / ISD / OTH. "ALL_OTHER_ITC"
                // is not one of them, so the payload was rejected on upload.
                ty = "OTH",
                iamt = totalPurchaseIgst,
                camt = totalPurchaseCgst,
                samt = totalPurchaseSgst,
                csamt = 0.0
            )

            val itcElg = Gstr3bItcElg(
                // 4(A) stays GROSS — a purchase return reverses in 4(B), it does not
                // reduce availment.
                itcAvl = listOf(itcItem),
                itcRev = if (returnTotals.hasReversal) listOf(
                    // itc_rev's enum is RUL (Rules 42/43) | OTH. A purchase return is "Others".
                    Gstr3bItcItem(
                        ty = "OTH",
                        iamt = returnTotals.reversalIgst,
                        camt = returnTotals.reversalCgst,
                        samt = returnTotals.reversalSgst,
                        csamt = 0.0
                    )
                ) else emptyList(),
                // 4(C) = 4(A) - 4(B). Was the SAME OBJECT as itc_avl[0], so net ITC was
                // structurally incapable of differing from gross. Allowed to go negative:
                // reversal exceeding availment adds to the period's liability.
                itcNet = Gstr3bItcNet(
                    iamt = returnTotals.netItcIgst,
                    camt = returnTotals.netItcCgst,
                    samt = returnTotals.netItcSgst,
                    csamt = 0.0
                )
            )

            val gstr3bPayload = Gstr3bPayload(
                gstin = clientGstin,
                retPeriod = currentPeriod,
                supDetails = supDetails,
                itcElg = itcElg
            )

            // 3. Combine Master Export Structure
            val combinedPayload = CombinedGstExportPayload(
                exportTimestamp = System.currentTimeMillis(),
                status = "SUCCESS",
                businessGstin = clientGstin,
                businessStateCode = clientStateCode,
                period = currentPeriod,
                gstr1 = gstr1Payload,
                gstr3b = gstr3bPayload
            )

            // 4. Convert to formatted JSON using Gson
            val gson = GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(combinedPayload)

            // 5. Save to local documents folder: GSTR1_GSTR3B_AUTO_[MMYYYY].json
            val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, "documents").apply { mkdirs() }
            
            val exportFileName = "GSTR1_GSTR3B_AUTO_${currentPeriod}.json"
            val exportFile = File(docsDir, exportFileName)
            exportFile.writeText(jsonString)

            Log.d(TAG, "Successfully exported GST JSON payload to: ${exportFile.absolutePath}")

            // 6. Update SharedPreferences status flag
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // The HSN gap is reported, not swallowed. Table 12 can only be built from
            // invoices carrying line items, and most entry paths write none — saying so
            // is the difference between an incomplete return and a silently wrong one.
            val hsnNote = if (hsnUnclassifiedCount > 0) {
                " | WARNING: $hsnUnclassifiedCount invoice(s) had no stock item, so they are " +
                    "absent from the HSN summary (Table 12). Add line items to include them."
            } else ""
            if (hsnUnclassifiedCount > 0) {
                Log.w(TAG, "HSN summary incomplete: $hsnUnclassifiedCount invoice(s) carry no line items")
            }
            val successStatus = "SUCCESS: Exported to $exportFileName on ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())}$hsnNote"
            prefs.edit().putString(KEY_LAST_EXPORT_STATUS, successStatus).apply()

            Result.success(exportFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error during automated GST export: ${e.message}", e)
            TelemetryEngine.recordThrowable(context, e, "GST_AUTOMATION_FAILURE")

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val failedStatus = "FAILED: ${e.message} on ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())}"
            prefs.edit().putString(KEY_LAST_EXPORT_STATUS, failedStatus).apply()

            Result.failure(e)
        }
    }

    /**
     * Maps a free-text unit to a GST Unit Quantity Code.
     *
     * Taking the first three characters emitted codes the portal rejects — the item
     * dialog's own hint suggests "Kg", which became "KG" where the valid code is "KGS";
     * likewise Litre to "LIT" (LTR), Gram to "GRA" (GMS), Piece to "PIE" (PCS). Only a
     * handful of units happened to survive intact.
     */
    private fun toUqc(unit: String): String {
        val key = unit.trim().lowercase(Locale.US)
        return when {
            key.startsWith("pc") || key.startsWith("pie") -> "PCS"
            key.startsWith("no") -> "NOS"
            key.startsWith("kg") || key.startsWith("kilo") -> "KGS"
            key.startsWith("gm") || key.startsWith("gra") -> "GMS"
            key.startsWith("ton") || key.startsWith("mt") -> "TON"
            key.startsWith("lt") || key.startsWith("lit") -> "LTR"
            key.startsWith("ml") -> "MLT"
            key.startsWith("mtr") || key.startsWith("met") -> "MTR"
            key.startsWith("cm") -> "CMS"
            key.startsWith("box") -> "BOX"
            key.startsWith("bag") -> "BAG"
            key.startsWith("btl") || key.startsWith("bot") -> "BTL"
            key.startsWith("doz") -> "DOZ"
            key.startsWith("set") -> "SET"
            key.startsWith("pac") || key.startsWith("pkt") -> "PAC"
            key.startsWith("sqm") -> "SQM"
            key.startsWith("sqf") -> "SQF"
            key.startsWith("unt") || key.startsWith("uni") -> "UNT"
            else -> "OTH"   // the schema's own catch-all, rather than an invented code
        }
    }

    fun getLastExportStatus(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_EXPORT_STATUS, "NOT_RUN") ?: "NOT_RUN"
    }

    private fun normalizeToZero(value: Double): Double {
        return if (value < 0.0) 0.0 else value
    }

    /**
     * Maps an Indian state name to its 2-digit GST state code, or null when the name is
     * blank or unrecognised. This used to end in `else -> "07"`, which quietly filed every
     * unknown state as Delhi; callers now surface the unresolved name instead.
     */
    /** Delegates to [GstStateCodes], which the audit checks share so the two agree. */
    private fun getStateCodeFromStateName(stateName: String): String? =
        GstStateCodes.codeFor(stateName)
}
