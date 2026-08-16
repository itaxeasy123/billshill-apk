package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.dao.LedgerWithBalance
import com.example.data.model.VoucherEntity
import java.io.File

object CsvExporter {

    /**
     * RFC 4180 escaping for any free-text cell. Every user-supplied value must go through
     * this — escaping field-by-field is how `groupName` ended up unescaped while `name`
     * was handled, letting a group called `Office, "Rent" Expenses` shift every column
     * after it.
     */
    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun generateLedgerSummaryCsv(ledgers: List<LedgerWithBalance>): String {
        val sb = StringBuilder()
        sb.append("Ledger ID,Ledger Account Name,Account Group,Category,Total Debit (INR),Total Credit (INR),Net Balance (INR),Balance Type\n")
        ledgers.forEach { l ->
            val balType = if (l.currentBalance >= 0) "DR" else "CR"
            val netBal = Math.abs(l.currentBalance)
            sb.append("${l.id},${csv(l.name)},${csv(l.groupName)},${l.category.name},${l.totalDebit},${l.totalCredit},$netBal,$balType\n")
        }
        return sb.toString()
    }

    fun generateTransactionHistoryCsv(vouchers: List<VoucherEntity>): String {
        val sb = StringBuilder()
        sb.append("Voucher No,Date,Voucher Type,Party/Account Name,Base Amount (INR),GST Amount (INR),Total Invoice Amount (INR),Narration\n")
        vouchers.forEach { v ->
            val baseAmount = v.totalAmount - v.gstAmount
            sb.append("${csv(v.voucherNo)},${csv(IndianFormatter.formatDate(v.date))},${v.voucherType.name},${csv(v.partyName)},$baseAmount,${v.gstAmount},${v.totalAmount},${csv(v.narration)}\n")
        }
        return sb.toString()
    }

    /**
     * @param buyerGstins party name (lowercased) to that party's GSTIN, so the classifier
     *   can tell a registered recipient from a consumer.
     * @param creditNotes SALES_RETURN vouchers for the same period — GSTR-1 Table 9B.
     *   Omitted entirely before, so this CSV reported gross outward supply while the
     *   GSTR-3B CSV beside it (which reads the netted getGstSummaryFlow) reported net.
     */
    fun generateGstr1Csv(
        salesVouchers: List<VoucherEntity>,
        user: com.example.data.model.UserEntity,
        buyerGstins: Map<String, String> = emptyMap(),
        creditNotes: List<VoucherEntity> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("GSTR-1 STATUTORY RETURN SUMMARY REPORT\n")
        sb.append("Business Name,${csv(user.businessName)},GSTIN,${csv(user.gstin)}\n\n")
        sb.append("Voucher No,Invoice Date,Party Name,Supply Category,Taxable Amount (INR),GST Tax Amount (INR),Total Invoice Value (INR),Interstate\n")
        salesVouchers.forEach { v ->
            // Was: `partyName.contains("gstin") || user.gstin.isNotBlank()` — testing the
            // SELLER's registration, so every sale by a registered business was B2B. Now
            // the shared classifier, against the buyer's GSTIN carried on the voucher's
            // party ledger (passed in by the caller).
            val supplyType = com.example.data.gst.GstClassifier
                .classify(buyerGstins[v.partyName.lowercase()], v.isInterstate, v.totalAmount)
                .label
            val taxable = v.totalAmount - v.gstAmount
            sb.append("${csv(v.voucherNo)},${csv(IndianFormatter.formatDate(v.date))},${csv(v.partyName)},$supplyType,$taxable,${v.gstAmount},${v.totalAmount},${v.isInterstate}\n")
        }
        // Credit notes carry POSITIVE amounts in the database and in the GSTR-1 JSON,
        // where ntty="C" supplies the direction. A flat CSV has no such tag, so the sign
        // written here is what makes the TOTAL row below reconcile.
        creditNotes.forEach { v ->
            val table = when (
                com.example.data.gst.GstClassifier
                    .classify(buyerGstins[v.partyName.lowercase()], v.isInterstate, v.totalAmount)
            ) {
                com.example.data.gst.SupplyCategory.B2B -> "Credit Note 9B (CDNR)"
                com.example.data.gst.SupplyCategory.B2CL -> "Credit Note 9B (CDNUR)"
                com.example.data.gst.SupplyCategory.B2CS -> "Credit Note (netted into B2CS)"
            }
            val taxable = v.totalAmount - v.gstAmount
            sb.append("${csv(v.voucherNo)},${csv(IndianFormatter.formatDate(v.date))},${csv(v.partyName)},$table,${-taxable},${-v.gstAmount},${-v.totalAmount},${v.isInterstate}\n")
        }
        val totalVal = salesVouchers.sumOf { it.totalAmount } - creditNotes.sumOf { it.totalAmount }
        val totalGst = salesVouchers.sumOf { it.gstAmount } - creditNotes.sumOf { it.gstAmount }
        // `.size`, not the list itself — interpolating List<VoucherEntity> dumped the whole
        // object graph (commas included) into the summary row of a statutory return export.
        sb.append("TOTAL,,,${salesVouchers.size} Invoices less ${creditNotes.size} Credit Notes,${totalVal - totalGst},$totalGst,$totalVal,\n")
        return sb.toString()
    }

    fun generateGstr3bCsv(vouchers: List<VoucherEntity>, gstSummary: com.example.data.dao.GstSummaryReport, user: com.example.data.model.UserEntity): String {
        val sb = StringBuilder()
        sb.append("GSTR-3B CONSOLIDATED TAX RETURN SUMMARY REPORT\n")
        sb.append("Business Name,${csv(user.businessName)},GSTIN,${csv(user.gstin)}\n\n")
        sb.append("Table Description,CGST (INR),SGST (INR),IGST (INR),Total Tax Amount (INR)\n")
        val totalOutput = gstSummary.totalOutputCgst + gstSummary.totalOutputSgst + gstSummary.totalOutputIgst
        sb.append("3.1 Outward Taxable Supplies (Output Liability),${gstSummary.totalOutputCgst},${gstSummary.totalOutputSgst},${gstSummary.totalOutputIgst},$totalOutput\n")
        val totalInput = gstSummary.totalInputCgst + gstSummary.totalInputSgst + gstSummary.totalInputIgst
        sb.append("4. Eligible Input Tax Credit (Purchases ITC),${gstSummary.totalInputCgst},${gstSummary.totalInputSgst},${gstSummary.totalInputIgst},$totalInput\n")
        // Rule 88A set-off, replacing four independently-netted-and-clamped cells that
        // could contradict each other by lakhs. `coerceAtLeast(0)` discarded the credit
        // that Rule 88A requires be pushed from IGST into CGST/SGST, and because max(0,x)
        // is not invertible nothing downstream could detect the loss.
        val setOff = com.example.data.gst.Rule88ASetOff.compute(
            outputIgst = gstSummary.totalOutputIgst,
            outputCgst = gstSummary.totalOutputCgst,
            outputSgst = gstSummary.totalOutputSgst,
            creditIgst = gstSummary.totalInputIgst,
            creditCgst = gstSummary.totalInputCgst,
            creditSgst = gstSummary.totalInputSgst
        )
        sb.append("6.1 Tax Payable in Cash (s.170 rounded),${setOff.cashCgst},${setOff.cashSgst},${setOff.cashIgst},${setOff.totalCash}\n")
        sb.append(
            "ITC Carried Forward,${setOff.cgst.creditCarriedForward}," +
                "${setOff.sgst.creditCarriedForward},${setOff.igst.creditCarriedForward}," +
                "${setOff.cgst.creditCarriedForward + setOff.sgst.creditCarriedForward + setOff.igst.creditCarriedForward}\n"
        )
        return sb.toString()
    }

    fun shareTextOrPdfReport(context: Context, filename: String, content: String, mimeType: String = "text/plain") {
        try {
            val cacheDir = File(context.cacheDir, "reports_export")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            // Callers build these names from user data — business name, ledger name — and
            // "M/s Foo" named a directory that does not exist, so the write failed. The
            // extension is preserved because mimeTypeFor() keys off it.
            val file = File(cacheDir, FileNames.safeWithExtension(filename, "report"))
            file.writeText(content)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_SUBJECT, "Tax & GST Report Export - $filename")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export / Share Tax Report"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Picks the MIME type from the filename.
     *
     * This function is used to share .xml (Tally/Marg import files) and .json (backups,
     * telemetry) as well as .csv, and it previously declared every one of them
     * `text/csv`. Share targets that filter by MIME — which the accounting-import apps
     * these exports exist for generally do — would not offer themselves as a
     * destination for a Tally XML file announced as a CSV.
     */
    private fun mimeTypeFor(filename: String): String = when {
        filename.endsWith(".xml", ignoreCase = true) -> "text/xml"
        filename.endsWith(".json", ignoreCase = true) -> "application/json"
        filename.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        else -> "text/csv"
    }

    /**
     * Writes [content] to the export cache and opens a share sheet.
     *
     * Returns true only if the file was written and the chooser actually started. The
     * caller must report failure to the user: this used to swallow every exception into
     * `printStackTrace()`, so a failed write or a missing chooser produced no file, no
     * share sheet, and no message — while the calling screen had often already claimed
     * success.
     */
    fun shareCsvFile(context: Context, filename: String, csvContent: String): Boolean {
        return try {
            val cacheDir = File(context.cacheDir, "csv_exports")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            // Same reason as shareTextOrPdfReport: these names are built from business and
            // ledger names, which routinely contain '/'. Sanitised after the extension is
            // read below, and safeWithExtension keeps the suffix so both the BOM decision
            // and mimeTypeFor() still see it.
            val file = File(cacheDir, FileNames.safeWithExtension(filename, "export"))
            // UTF-8 BOM, CSV only: Excel on Windows otherwise decodes a BOM-less file
            // with the system code page, turning Devanagari party and business names
            // into mojibake. It must NOT be added to the JSON and XML files that also
            // go through here \u2014 a leading BOM makes JSONObject reject a backup outright,
            // which would break the restore path.
            val isCsv = filename.endsWith(".csv", ignoreCase = true)
            file.writeText(if (isCsv) "\uFEFF" + csvContent else csvContent)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(filename)
                putExtra(Intent.EXTRA_SUBJECT, "Accounting Export - $filename")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Accounting Data"))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
