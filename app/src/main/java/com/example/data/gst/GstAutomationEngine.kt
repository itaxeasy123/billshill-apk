package com.example.data.gst

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.ai.LocalAiReconciliationEngine
import com.example.data.db.AppDatabase
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.utils.TelemetryEngine
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
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

            // 2. Fetch all vouchers from Room DB
            val allVouchers = accountingDao.getAllVouchersSync()
            val salesVouchers = allVouchers.filter { it.voucherType == VoucherType.SALES || it.voucherType == VoucherType.Sale }
            val purchaseVouchers = allVouchers.filter { it.voucherType == VoucherType.PURCHASE || it.voucherType == VoucherType.Purchase }

            Log.d(TAG, "Found ${salesVouchers.size} sales vouchers and ${purchaseVouchers.size} purchase vouchers.")

            // --- GSTR-1 AGGREGATION ---
            val b2bMap = mutableMapOf<String, MutableList<Gstr1Invoice>>()
            val b2csItems = mutableListOf<Gstr1B2csItem>()
            val hsnMap = mutableMapOf<String, Gstr1HsnDetail>()

            var totalSalesTaxable = 0.0
            var totalSalesCgst = 0.0
            var totalSalesSgst = 0.0
            var totalSalesIgst = 0.0

            var invoiceSeqStart = ""
            var invoiceSeqEnd = ""
            var salesInvoiceCount = 0

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

                val isInterstate = posStateCode != clientStateCode

                // Calculate & Normalize Taxable Value
                var taxableVal = voucher.totalAmount - voucher.gstAmount
                var totalGst = voucher.gstAmount

                // Guardrail: Normalize negative values to 0.0 & log telemetry
                if (taxableVal < 0 || totalGst < 0) {
                    val errorNote = "Negative value detected in Sales Voucher #${voucher.voucherNo}: val=$taxableVal, gst=$totalGst. Normalizing to 0.0."
                    Log.w(TAG, errorNote)
                    TelemetryEngine.recordThrowable(context, IllegalArgumentException(errorNote), "GST_EXPORT_GUARDRAIL")
                    taxableVal = normalizeToZero(taxableVal)
                    totalGst = normalizeToZero(totalGst)
                }

                // Automated Tax Splitting Rules
                val split = if (isInterstate) {
                    TaxSplit(cgst = 0.0, sgst = 0.0, igst = totalGst) // 100% IGST
                } else {
                    TaxSplit(cgst = totalGst / 2.0, sgst = totalGst / 2.0, igst = 0.0) // 50-50 CGST & SGST
                }

                totalSalesTaxable += taxableVal
                totalSalesCgst += split.cgst
                totalSalesSgst += split.sgst
                totalSalesIgst += split.igst

                salesInvoiceCount++
                val sanitizedInum = LocalAiReconciliationEngine.sanitizeInvoiceNumber(voucher.voucherNo)
                if (index == 0) invoiceSeqStart = sanitizedInum
                invoiceSeqEnd = sanitizedInum

                val dateStr = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(voucher.date))

                val invItem = Gstr1InvoiceItem(
                    num = 1,
                    itmDet = Gstr1ItmDet(
                        rt = 18.0,
                        txval = taxableVal,
                        iamt = split.igst,
                        camt = split.cgst,
                        samt = split.sgst,
                        csamt = 0.0
                    )
                )

                if (partyGstin.length >= 15) {
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
                } else {
                    // B2CS Consumer
                    b2csItems.add(
                        Gstr1B2csItem(
                            splyTy = if (isInterstate) "INTER" else "INTRA",
                            pos = posStateCode,
                            rt = 18.0,
                            txval = taxableVal,
                            iamt = split.igst,
                            camt = split.cgst,
                            samt = split.sgst,
                            csamt = 0.0
                        )
                    )
                }

                // HSN Summary Rollup
                val hsnCode = "9983" // Default HSN for services/general trade
                val existingHsn = hsnMap[hsnCode]
                if (existingHsn != null) {
                    hsnMap[hsnCode] = existingHsn.copy(
                        qty = existingHsn.qty + 1.0,
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
                        desc = "General Commercial Supplies",
                        uqc = "NOS",
                        qty = 1.0,
                        totalVal = voucher.totalAmount,
                        txval = taxableVal,
                        iamt = split.igst,
                        camt = split.cgst,
                        samt = split.sgst,
                        csamt = 0.0
                    )
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
                }
            )

            val gstr1Payload = Gstr1Payload(
                gstin = clientGstin,
                fp = currentPeriod,
                gt = totalSalesTaxable,
                curGt = totalSalesTaxable,
                b2b = b2bGroups,
                b2cs = b2csItems,
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

                val isInterstate = posStateCode != clientStateCode

                var taxableVal = voucher.totalAmount - voucher.gstAmount
                var totalGst = voucher.gstAmount

                if (taxableVal < 0 || totalGst < 0) {
                    val errorNote = "Negative value in Purchase Voucher #${voucher.voucherNo}: val=$taxableVal, gst=$totalGst. Normalizing."
                    Log.w(TAG, errorNote)
                    TelemetryEngine.recordThrowable(context, IllegalArgumentException(errorNote), "GST_EXPORT_GUARDRAIL")
                    taxableVal = normalizeToZero(taxableVal)
                    totalGst = normalizeToZero(totalGst)
                }

                val split = if (isInterstate) {
                    TaxSplit(cgst = 0.0, sgst = 0.0, igst = totalGst)
                } else {
                    TaxSplit(cgst = totalGst / 2.0, sgst = totalGst / 2.0, igst = 0.0)
                }

                totalPurchaseTaxable += taxableVal
                totalPurchaseCgst += split.cgst
                totalPurchaseSgst += split.sgst
                totalPurchaseIgst += split.igst
            }

            val supDetails = Gstr3bSupDetails(
                osupDet = Gstr3bTaxTuple(
                    txval = totalSalesTaxable,
                    iamt = totalSalesIgst,
                    camt = totalSalesCgst,
                    samt = totalSalesSgst,
                    csamt = 0.0
                )
            )

            val itcItem = Gstr3bItcItem(
                ty = "ALL_OTHER_ITC", // Strict government structural type tag
                iamt = totalPurchaseIgst,
                camt = totalPurchaseCgst,
                samt = totalPurchaseSgst,
                csamt = 0.0
            )

            val itcElg = Gstr3bItcElg(
                itcAvl = listOf(itcItem),
                itcNet = itcItem
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
            val successStatus = "SUCCESS: Exported to $exportFileName on ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())}"
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
    private fun getStateCodeFromStateName(stateName: String): String? {
        return when (stateName.trim().lowercase(Locale.US)) {
            "jammu & kashmir", "jammu and kashmir" -> "01"
            "himachal pradesh" -> "02"
            "punjab" -> "03"
            "chandigarh" -> "04"
            "uttarakhand" -> "05"
            "haryana" -> "06"
            "delhi", "new delhi" -> "07"
            "rajasthan" -> "08"
            "uttar pradesh" -> "09"
            "bihar" -> "10"
            "sikkim" -> "11"
            "arunachal pradesh" -> "12"
            "nagaland" -> "13"
            "manipur" -> "14"
            "mizoram" -> "15"
            "tripura" -> "16"
            "meghalaya" -> "17"
            "assam" -> "18"
            "west bengal" -> "19"
            "jharkhand" -> "20"
            "odisha" -> "21"
            "chhattisgarh" -> "22"
            "madhya pradesh" -> "23"
            "gujarat" -> "24"
            "daman & diu", "dadra & nagar haveli" -> "26"
            "maharashtra" -> "27"
            "andhra pradesh" -> "28"
            "karnataka" -> "29"
            "goa" -> "30"
            "lakshadweep" -> "31"
            "kerala" -> "32"
            "tamil nadu" -> "33"
            "puducherry" -> "34"
            "andaman & nicobar islands" -> "35"
            "telangana" -> "36"
            "ladakh" -> "38"
            else -> null
        }
    }
}
