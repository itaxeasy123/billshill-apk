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

    fun generateGstr1Csv(salesVouchers: List<VoucherEntity>, user: com.example.data.model.UserEntity): String {
        val sb = StringBuilder()
        sb.append("GSTR-1 STATUTORY RETURN SUMMARY REPORT\n")
        sb.append("Business Name,${csv(user.businessName)},GSTIN,${csv(user.gstin)}\n\n")
        sb.append("Voucher No,Invoice Date,Party Name,Supply Category,Taxable Amount (INR),GST Tax Amount (INR),Total Invoice Value (INR),Interstate\n")
        salesVouchers.forEach { v ->
            val supplyType = if (v.partyName.lowercase().contains("gstin") || user.gstin.isNotBlank()) "B2B" else if (v.isInterstate && v.totalAmount > 250000.0) "B2C Large" else "B2C Small"
            val taxable = v.totalAmount - v.gstAmount
            sb.append("${csv(v.voucherNo)},${csv(IndianFormatter.formatDate(v.date))},${csv(v.partyName)},$supplyType,$taxable,${v.gstAmount},${v.totalAmount},${v.isInterstate}\n")
        }
        val totalVal = salesVouchers.sumOf { it.totalAmount }
        val totalGst = salesVouchers.sumOf { it.gstAmount }
        // `.size`, not the list itself — interpolating List<VoucherEntity> dumped the whole
        // object graph (commas included) into the summary row of a statutory return export.
        sb.append("TOTAL,,,${salesVouchers.size} Invoices,${totalVal - totalGst},$totalGst,$totalVal,\n")
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
        val netCgst = gstSummary.totalOutputCgst - gstSummary.totalInputCgst
        val netSgst = gstSummary.totalOutputSgst - gstSummary.totalInputSgst
        val netIgst = gstSummary.totalOutputIgst - gstSummary.totalInputIgst
        val netTotal = totalOutput - totalInput
        sb.append("6.1 Net Tax Payable / ITC Balance,${if (netCgst > 0) netCgst else 0.0},${if (netSgst > 0) netSgst else 0.0},${if (netIgst > 0) netIgst else 0.0},${if (netTotal > 0) netTotal else 0.0}\n")
        return sb.toString()
    }

    fun shareTextOrPdfReport(context: Context, filename: String, content: String, mimeType: String = "text/plain") {
        try {
            val cacheDir = File(context.cacheDir, "reports_export")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, filename)
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

    fun shareCsvFile(context: Context, filename: String, csvContent: String) {
        try {
            val cacheDir = File(context.cacheDir, "csv_exports")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val file = File(cacheDir, filename)
            file.writeText(csvContent)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Accounting Export - $filename")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Accounting CSV"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
