package com.example.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.dao.LedgerTxEntry
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import java.io.File
import java.io.FileOutputStream

object PdfInvoiceGenerator {

    fun generateAndShareLedgerPdf(
        context: Context,
        ledgerName: String,
        groupName: String,
        currentBalance: Double,
        transactions: List<LedgerTxEntry>,
        user: UserEntity
    ) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.rgb(103, 58, 183)
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val subHeaderPaint = Paint().apply {
            color = Color.rgb(60, 60, 60)
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            isFakeBoldText = true
            isAntiAlias = true
        }

        var y = 35f

        // Company Banner
        canvas.drawRect(20f, y, 575f, y + 65f, Paint().apply { color = Color.rgb(245, 240, 255) })
        canvas.drawRect(20f, y, 575f, y + 65f, Paint().apply { color = Color.rgb(103, 58, 183); style = Paint.Style.STROKE; strokeWidth = 1.5f })
        
        // Logo
        canvas.drawCircle(50f, y + 32f, 20f, Paint().apply { color = Color.rgb(103, 58, 183) })
        canvas.drawText(user.businessName.take(1).uppercase(), 45f, y + 38f, Paint().apply { color = Color.WHITE; textSize = 16f; isFakeBoldText = true; isAntiAlias = true })

        canvas.drawText(user.businessName.ifBlank { "Apex Enterprises India" }, 80f, y + 25f, headerPaint)
        val ownerName = "Proprietor: ${user.firstName} ${user.middleName} ${user.surname}".replace("\\s+".toRegex(), " ").trim()
        canvas.drawText("$ownerName | GSTIN: ${user.gstin}", 80f, y + 42f, paint.apply { textSize = 9.5f })
        canvas.drawText("${user.address}, ${user.city}, ${user.state} | Ph: ${user.phoneNumber}", 80f, y + 56f, paint.apply { textSize = 9.5f })

        y += 80f

        // Title
        canvas.drawText("STATEMENT OF ACCOUNT / LEDGER DISPLAY", 160f, y, subHeaderPaint.apply { color = Color.rgb(103, 58, 183) })
        y += 18f

        // Meta Box
        canvas.drawRect(20f, y, 575f, y + 36f, Paint().apply { color = Color.rgb(250, 250, 250) })
        canvas.drawRect(20f, y, 575f, y + 36f, Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f })
        canvas.drawText("Ledger Name: $ledgerName", 30f, y + 16f, boldPaint)
        canvas.drawText("Group: $groupName", 30f, y + 30f, paint.apply { textSize = 9.5f })
        canvas.drawText("Closing Balance: ${IndianFormatter.formatRupee(Math.abs(currentBalance))}", 380f, y + 22f, boldPaint.apply { color = Color.rgb(103, 58, 183); textSize = 11f })

        y += 50f

        // Table Header
        canvas.drawRect(20f, y, 575f, y + 22f, Paint().apply { color = Color.rgb(103, 58, 183) })
        val whiteHeader = Paint().apply { color = Color.WHITE; textSize = 10f; isFakeBoldText = true; isAntiAlias = true }
        canvas.drawText("Date", 28f, y + 15f, whiteHeader)
        canvas.drawText("Voucher No & Type", 95f, y + 15f, whiteHeader)
        canvas.drawText("Particulars / Party", 220f, y + 15f, whiteHeader)
        canvas.drawText("Debit (Dr)", 420f, y + 15f, whiteHeader)
        canvas.drawText("Credit (Cr)", 500f, y + 15f, whiteHeader)

        y += 22f

        var totDr = 0.0
        var totCr = 0.0

        // Transactions Table Rows
        for ((idx, tx) in transactions.withIndex()) {
            if (y > 750f) break // Single page limit check
            totDr += tx.debitAmount
            totCr += tx.creditAmount

            val rowBg = if (idx % 2 == 0) Color.WHITE else Color.rgb(248, 248, 252)
            canvas.drawRect(20f, y, 575f, y + 20f, Paint().apply { color = rowBg })
            canvas.drawRect(20f, y, 575f, y + 20f, Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f })

            canvas.drawText(IndianFormatter.formatDate(tx.date), 28f, y + 14f, paint.apply { textSize = 9f })
            canvas.drawText("${tx.voucherType.name} #${tx.voucherNo}", 95f, y + 14f, boldPaint.apply { textSize = 9f })
            canvas.drawText(tx.partyName.take(24), 220f, y + 14f, paint.apply { textSize = 9f })
            canvas.drawText(if (tx.debitAmount > 0) IndianFormatter.formatRupee(tx.debitAmount, false) else "-", 420f, y + 14f, paint)
            canvas.drawText(if (tx.creditAmount > 0) IndianFormatter.formatRupee(tx.creditAmount, false) else "-", 500f, y + 14f, paint)

            y += 20f
        }

        // Totals Row
        y += 5f
        canvas.drawRect(20f, y, 575f, y + 24f, Paint().apply { color = Color.rgb(240, 235, 250) })
        canvas.drawRect(20f, y, 575f, y + 24f, Paint().apply { color = Color.rgb(103, 58, 183); style = Paint.Style.STROKE; strokeWidth = 1f })
        canvas.drawText("TOTAL TRANSACTION PERIOD", 30f, y + 16f, boldPaint.apply { textSize = 10f })
        canvas.drawText(IndianFormatter.formatRupee(totDr), 415f, y + 16f, boldPaint)
        canvas.drawText(IndianFormatter.formatRupee(totCr), 495f, y + 16f, boldPaint)

        y += 50f

        // Footer Signatory
        canvas.drawText("For ${user.businessName}", 400f, y, boldPaint)
        y += 40f
        canvas.drawLine(380f, y, 550f, y, Paint().apply { color = Color.GRAY; strokeWidth = 1f })
        canvas.drawText("Authorized Signatory / Accountant", 400f, y + 14f, paint.apply { textSize = 9f })

        pdfDocument.finishPage(page)

        try {
            val fileName = "Ledger_Statement_${ledgerName.replace("\\s+".toRegex(), "_")}.pdf"
            val pdfFile = File(context.cacheDir, fileName)
            val fos = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fos)
            fos.close()
            pdfDocument.close()

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Ledger Statement - $ledgerName")
                putExtra(Intent.EXTRA_TEXT, "Statement of Account for $ledgerName from ${user.businessName}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Print / Share Ledger Statement PDF")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error generating Ledger PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun generateAndSharePdf(context: Context, voucher: VoucherEntity, user: UserEntity) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Size: 595 x 842 pt
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.rgb(103, 58, 183) // Royal Purple Primary
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val subHeaderPaint = Paint().apply {
            color = Color.rgb(60, 60, 60)
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isFakeBoldText = true
            isAntiAlias = true
        }

        var y = 35f

        // 1. Company Logo Graphic / Top Header Banner
        canvas.drawRect(20f, y, 575f, y + 65f, Paint().apply { color = Color.rgb(245, 240, 255) })
        canvas.drawRect(20f, y, 575f, y + 65f, Paint().apply { color = Color.rgb(103, 58, 183); style = Paint.Style.STROKE; strokeWidth = 1.5f })
        
        // Brand Logo Icon Graphic
        canvas.drawCircle(50f, y + 32f, 20f, Paint().apply { color = Color.rgb(103, 58, 183) })
        val whiteLogoPaint = Paint().apply { color = Color.WHITE; textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        canvas.drawText(user.businessName.take(1).uppercase(), 45f, y + 38f, whiteLogoPaint)

        // Company Details
        canvas.drawText(user.businessName.ifBlank { "Apex Enterprises India" }, 80f, y + 25f, headerPaint)
        val ownerFullName = "Proprietor: ${user.firstName} ${user.middleName} ${user.surname}".replace("\\s+".toRegex(), " ").trim()
        canvas.drawText("$ownerFullName | Father Name: ${user.fatherName.ifBlank { "N/A" }}", 80f, y + 42f, paint.apply { textSize = 10f })
        canvas.drawText("GSTIN: ${user.gstin} | Mobile: ${user.phoneNumber} | Email: ${user.email}", 80f, y + 56f, paint.apply { textSize = 10f })

        y += 80f

        // 2. Voucher Document Type Title
        val docTitle = when (voucher.voucherType.name) {
            "SALES", "Sale" -> "TAX INVOICE"
            "PURCHASE", "Purchase" -> "PURCHASE VOUCHER"
            "RECEIPT", "Receipt" -> "RECEIPT VOUCHER"
            "PAYMENT", "Payment" -> "PAYMENT VOUCHER"
            else -> "ACCOUNTING VOUCHER"
        }
        canvas.drawText(docTitle, 225f, y, subHeaderPaint.apply { textSize = 15f; color = Color.rgb(103, 58, 183) })
        y += 20f

        // 3. Voucher Meta Info Row
        canvas.drawText("Voucher No: ${voucher.voucherNo}", 30f, y, boldPaint)
        canvas.drawText("Date: ${IndianFormatter.formatDate(voucher.date)}", 430f, y, boldPaint)
        y += 18f

        // 4. Billed To / Party Box
        canvas.drawRect(20f, y, 575f, y + 45f, Paint().apply { color = Color.rgb(250, 250, 250) })
        canvas.drawRect(20f, y, 575f, y + 45f, Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.8f })

        val partyLabel = when (voucher.voucherType.name) {
            "SALES", "Sale" -> "Customer Name:"
            "PURCHASE", "Purchase" -> "Party / Vendor Name:"
            "RECEIPT", "Receipt" -> "Payer / Customer Name:"
            "PAYMENT", "Payment" -> "Payee / Vendor Name:"
            else -> "Party Name:"
        }
        canvas.drawText(partyLabel, 30f, y + 18f, boldPaint)
        canvas.drawText(voucher.partyName, 160f, y + 18f, boldPaint.apply { color = Color.rgb(103, 58, 183) })
        if (voucher.narration.isNotBlank()) {
            canvas.drawText("Notes: ${voucher.narration}", 30f, y + 36f, paint.apply { textSize = 9.5f; color = Color.DKGRAY })
        }
        y += 60f

        // 5. Table Header
        canvas.drawRect(20f, y, 575f, y + 24f, Paint().apply { color = Color.rgb(103, 58, 183) })
        val whiteHeader = Paint().apply { color = Color.WHITE; textSize = 10.5f; isFakeBoldText = true; isAntiAlias = true }
        canvas.drawText("Particulars / Description", 30f, y + 16f, whiteHeader)
        canvas.drawText("Taxable Value", 320f, y + 16f, whiteHeader)
        canvas.drawText("GST Amount", 420f, y + 16f, whiteHeader)
        canvas.drawText("Total Amount", 500f, y + 16f, whiteHeader)
        y += 24f

        // 6. Table Row Data
        val amount = voucher.totalAmount
        val gstAmount = voucher.gstAmount
        val taxableValue = amount - gstAmount

        canvas.drawRect(20f, y, 575f, y + 35f, Paint().apply { color = Color.WHITE })
        canvas.drawRect(20f, y, 575f, y + 35f, Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE })

        canvas.drawText(voucher.partyName + " (" + docTitle + ")", 30f, y + 22f, paint.apply { textSize = 10.5f })
        canvas.drawText(IndianFormatter.formatRupee(taxableValue), 320f, y + 22f, paint)
        canvas.drawText(IndianFormatter.formatRupee(gstAmount), 420f, y + 22f, paint)
        canvas.drawText(IndianFormatter.formatRupee(amount), 500f, y + 22f, boldPaint)
        y += 50f

        // 7. Statutory Tax & Summary Box
        canvas.drawRect(20f, y, 575f, y + 60f, Paint().apply { color = Color.rgb(252, 252, 252) })
        canvas.drawRect(20f, y, 575f, y + 60f, Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE })

        if (voucher.isInterstate) {
            canvas.drawText("IGST Tax: ${IndianFormatter.formatRupee(gstAmount)}", 30f, y + 20f, paint.apply { textSize = 10f })
        } else {
            canvas.drawText("CGST Tax: ${IndianFormatter.formatRupee(gstAmount / 2.0)} | SGST Tax: ${IndianFormatter.formatRupee(gstAmount / 2.0)}", 30f, y + 20f, paint.apply { textSize = 10f })
        }
        canvas.drawText("Amount in Words: ${IndianFormatter.convertNumberToWords(amount)}", 30f, y + 42f, boldPaint.apply { textSize = 10f })
        y += 90f

        // 8. Signatory & Footer
        canvas.drawText("For ${user.businessName}", 400f, y, boldPaint)
        
        // Draw Authorized Signature Bitmap if saved
        val sigFile = File(context.cacheDir, "authorized_signature.png")
        if (sigFile.exists()) {
            try {
                val sigBitmap = android.graphics.BitmapFactory.decodeFile(sigFile.absolutePath)
                if (sigBitmap != null) {
                    val destRect = android.graphics.RectF(400f, y + 5f, 520f, y + 40f)
                    canvas.drawBitmap(sigBitmap, null, destRect, Paint().apply { isFilterBitmap = true })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        y += 45f
        canvas.drawLine(380f, y, 550f, y, Paint().apply { color = Color.GRAY; strokeWidth = 1f })
        canvas.drawText("Authorized Signatory / Seal", 410f, y + 15f, paint.apply { textSize = 9.5f })

        pdfDocument.finishPage(page)

        // Save PDF to cache directory and open Android Share Intent
        try {
            val pdfFile = File(context.cacheDir, "Invoice_${voucher.voucherNo}.pdf")
            val fos = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fos)
            fos.close()
            pdfDocument.close()

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$docTitle - ${voucher.voucherNo}")
                putExtra(Intent.EXTRA_TEXT, "$docTitle for ${voucher.partyName} of total amount ${IndianFormatter.formatRupee(voucher.totalAmount)} from ${user.businessName}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Print / Share / Email A4 PDF Invoice")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error generating PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun printPdfFile(context: Context, pdfFile: File) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (printManager != null) {
            val jobName = "Print_Invoice_${pdfFile.name}"
            val printAdapter = object : android.print.PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: android.print.PrintAttributes?,
                    newAttributes: android.print.PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: android.os.Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = android.print.PrintDocumentInfo.Builder(jobName)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out android.print.PageRange>?,
                    destination: android.os.ParcelFileDescriptor?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        val input = java.io.FileInputStream(pdfFile)
                        val output = java.io.FileOutputStream(destination?.fileDescriptor)
                        input.copyTo(output)
                        callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }
            printManager.print(jobName, printAdapter, null)
        } else {
            Toast.makeText(context, "Printing service not available", Toast.LENGTH_SHORT).show()
        }
    }
}
