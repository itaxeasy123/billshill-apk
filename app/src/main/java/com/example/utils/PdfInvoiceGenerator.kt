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

    /**
     * Returns true only if the PDF was written and the share sheet actually started.
     *
     * [ledgerId] discriminates the file name: two differently-named ledgers can sanitise
     * to the same safe name, and a statement is a document that gets emailed to a party.
     */
    fun generateAndShareLedgerPdf(
        context: Context,
        ledgerName: String,
        ledgerId: Long,
        groupName: String,
        currentBalance: Double,
        transactions: List<LedgerTxEntry>,
        user: UserEntity
    ): Boolean {
        val pdfDocument = PdfDocument()
        // page/canvas are vars: PdfDocument is multi-page by design, each page carrying its
        // own number, and this statement used to throw away every transaction that did not
        // fit on the first one.
        var pageNumber = 1
        var page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var canvas = page.canvas

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

        // Letterhead. The business name used to fall back to the invented firm
        // "Apex Enterprises India" whenever the profile was blank, and the identity and
        // address lines printed their separators around empty fields. Blank fields are
        // now dropped so the letterhead shows only what the user actually entered.
        canvas.drawText(user.businessName.ifBlank { "—" }, 80f, y + 25f, headerPaint)
        val ownerName = "${user.firstName} ${user.middleName} ${user.surname}".replace("\\s+".toRegex(), " ").trim()
        val identityLine = listOfNotNull(
            ownerName.takeIf { it.isNotBlank() }?.let { "Proprietor: $it" },
            user.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
        ).joinToString(" | ")
        if (identityLine.isNotBlank()) {
            canvas.drawText(identityLine, 80f, y + 42f, paint.apply { textSize = 9.5f })
        }
        val addressLine = listOfNotNull(
            listOf(user.address, user.city, user.state).filter { it.isNotBlank() }
                .joinToString(", ").takeIf { it.isNotBlank() },
            user.phoneNumber.takeIf { it.isNotBlank() }?.let { "Ph: $it" }
        ).joinToString(" | ")
        if (addressLine.isNotBlank()) {
            canvas.drawText(addressLine, 80f, y + 56f, paint.apply { textSize = 9.5f })
        }

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

        // A dedicated paint: the `paint.apply { }` idiom used through this file mutates the
        // shared object, so a colour set on it leaks into every later row.
        val pageNumPaint = Paint().apply { color = Color.GRAY; textSize = 8f; isAntiAlias = true }
        val whiteHeader = Paint().apply { color = Color.WHITE; textSize = 10f; isFakeBoldText = true; isAntiAlias = true }

        val rowHeight = 20f
        val tableBottom = 760f
        val contPageTableTop = 75f
        val totalsBlockHeight = 29f
        val footerBlockHeight = 105f

        // Simulated with the same arithmetic the draw loop uses, so "Page 2 of 4" and the
        // layout cannot disagree.
        val totalPages = run {
            var pages = 1
            var cursor = y + 22f
            repeat(transactions.size) {
                if (cursor + rowHeight > tableBottom) { pages++; cursor = contPageTableTop + 22f }
                cursor += rowHeight
            }
            if (cursor + totalsBlockHeight + footerBlockHeight > tableBottom) pages++
            pages
        }

        // Redrawn at the top of every page — a continuation page without it is four
        // unlabelled columns of numbers.
        val drawColumnHeader = { top: Float ->
            canvas.drawRect(20f, top, 575f, top + 22f, Paint().apply { color = Color.rgb(103, 58, 183) })
            canvas.drawText("Date", 28f, top + 15f, whiteHeader)
            canvas.drawText("Voucher No & Type", 95f, top + 15f, whiteHeader)
            canvas.drawText("Particulars / Party", 220f, top + 15f, whiteHeader)
            canvas.drawText("Debit (Dr)", 420f, top + 15f, whiteHeader)
            canvas.drawText("Credit (Cr)", 500f, top + 15f, whiteHeader)
        }

        // Table Header
        drawColumnHeader(y)
        y += 22f

        val startNextPage = {
            canvas.drawText("Page $pageNumber of $totalPages", 495f, 820f, pageNumPaint)
            pdfDocument.finishPage(page)
            pageNumber++
            page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas
            y = 35f
            canvas.drawText(
                "Statement of Account: $ledgerName (continued)", 20f, y,
                subHeaderPaint.apply { textSize = 11f; color = Color.rgb(103, 58, 183) }
            )
            y += 18f
            drawColumnHeader(y)
            y += 22f
        }

        var totDr = 0.0
        var totCr = 0.0

        // Transactions Table Rows
        for ((idx, tx) in transactions.withIndex()) {
            // Every transaction is drawn and every transaction is counted. The old
            // `if (y > 750f) break` printed the first 28 rows, totalled only those 28, and
            // labelled that sum "TOTAL TRANSACTION PERIOD" — a statement of account that
            // under-reports the account, with no continuation marker of any kind.
            if (y + rowHeight > tableBottom) startNextPage()
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

        // Totals Row. The totals and the signatory block must not split across a page
        // break — a page ending in a bare grand total, signed overleaf, is not a document.
        if (y + totalsBlockHeight + footerBlockHeight > tableBottom) startNextPage()
        y += 5f
        canvas.drawRect(20f, y, 575f, y + 24f, Paint().apply { color = Color.rgb(240, 235, 250) })
        canvas.drawRect(20f, y, 575f, y + 24f, Paint().apply { color = Color.rgb(103, 58, 183); style = Paint.Style.STROKE; strokeWidth = 1f })
        canvas.drawText("TOTAL — ${transactions.size} TRANSACTIONS", 30f, y + 16f, boldPaint.apply { textSize = 10f })
        canvas.drawText(IndianFormatter.formatRupee(totDr), 415f, y + 16f, boldPaint)
        canvas.drawText(IndianFormatter.formatRupee(totCr), 495f, y + 16f, boldPaint)

        y += 50f

        // Footer Signatory (omitted rather than printing a dangling "For ")
        if (user.businessName.isNotBlank()) {
            canvas.drawText("For ${user.businessName}", 400f, y, boldPaint)
        }
        y += 40f
        canvas.drawLine(380f, y, 550f, y, Paint().apply { color = Color.GRAY; strokeWidth = 1f })
        canvas.drawText("Authorized Signatory / Accountant", 400f, y + 14f, paint.apply { textSize = 9f })

        canvas.drawText("Page $pageNumber of $totalPages", 495f, 820f, pageNumPaint)
        pdfDocument.finishPage(page)

        return try {
            // Collapsing whitespace was the whole of the old sanitisation, so '/' passed
            // straight through. Ledger names are unvalidated user text and party ledgers
            // are auto-created from typed party names, where "M/s Sharma Traders" is the
            // standard Indian form — and the app seeds a group called "Suspense A/c".
            val dir = File(context.cacheDir, "invoice_pdfs").apply { mkdirs() }
            val safeName = FileNames.safe(ledgerName, fallback = "ledger")
            val pdfFile = File(dir, "Ledger_Statement_${safeName}_$ledgerId.pdf")
            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Ledger Statement - $ledgerName")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Statement of Account for $ledgerName" +
                        if (user.businessName.isNotBlank()) " from ${user.businessName}" else ""
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Print / Share Ledger Statement PDF")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error generating Ledger PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        } finally {
            pdfDocument.close()
        }
    }

    /** Returns true only if the PDF was written and the share sheet actually started. */
    fun generateAndSharePdf(context: Context, voucher: VoucherEntity, user: UserEntity): Boolean {
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

        // Company Details on the invoice letterhead. The business name used to fall back
        // to the invented firm "Apex Enterprises India"; the statutory line printed
        // "GSTIN: " with nothing after it. Blank fields are now omitted entirely so a
        // half-filled profile cannot read as a complete registered identity.
        canvas.drawText(user.businessName.ifBlank { "—" }, 80f, y + 25f, headerPaint)
        val ownerFullName = "${user.firstName} ${user.middleName} ${user.surname}".replace("\\s+".toRegex(), " ").trim()
        val personLine = listOfNotNull(
            ownerFullName.takeIf { it.isNotBlank() }?.let { "Proprietor: $it" },
            user.fatherName.takeIf { it.isNotBlank() }?.let { "Father Name: $it" }
        ).joinToString(" | ")
        if (personLine.isNotBlank()) {
            canvas.drawText(personLine, 80f, y + 42f, paint.apply { textSize = 10f })
        }
        val contactLine = listOfNotNull(
            user.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" },
            user.phoneNumber.takeIf { it.isNotBlank() }?.let { "Mobile: $it" },
            user.email.takeIf { it.isNotBlank() }?.let { "Email: $it" }
        ).joinToString(" | ")
        if (contactLine.isNotBlank()) {
            canvas.drawText(contactLine, 80f, y + 56f, paint.apply { textSize = 10f })
        }

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
        val taxableValue = GstCalculationService.taxableValueOf(amount, gstAmount)
        val (cgstAmt, sgstAmt, _) = GstCalculationService.splitForVoucher(gstAmount, voucher.isInterstate)

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
            // Was gstAmount / 2.0 twice, so the two heads could sum to a paisa more than
            // the GST total printed on the same page.
            canvas.drawText("CGST Tax: ${IndianFormatter.formatRupee(cgstAmt)} | SGST Tax: ${IndianFormatter.formatRupee(sgstAmt)}", 30f, y + 20f, paint.apply { textSize = 10f })
        }
        canvas.drawText("Amount in Words: ${IndianFormatter.convertNumberToWords(amount)}", 30f, y + 42f, boldPaint.apply { textSize = 10f })
        y += 90f

        // 8. Signatory & Footer (omitted rather than printing a dangling "For ")
        if (user.businessName.isNotBlank()) {
            canvas.drawText("For ${user.businessName}", 400f, y, boldPaint)
        }

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
        return try {
            // Invoice numbers carry '/' by design — DatabaseSeedEngine seeds "INV/2026-27/"
            // for sales and an equivalent for all eight voucher types — so interpolating one
            // straight into a path named two directories that do not exist, and every PDF
            // export failed in a default book with an internal-path Toast.
            //
            // The row id is appended because sanitising is not injective and must not be
            // relied on to be: the voucher prefix is a free-text field, so a book whose
            // prefix was edited from "INV/26-27/" to "INV-26-27-" can hold two vouchers
            // that sanitise to one name, and a cloud restore can supply an empty
            // voucherNo. Overwriting one tax invoice with another and sharing it under the
            // right name is far worse than the loud failure this replaces.
            val dir = File(context.cacheDir, "invoice_pdfs").apply { mkdirs() }
            val safeNo = FileNames.safe(voucher.voucherNo, fallback = "voucher")
            val pdfFile = File(dir, "Invoice_${safeNo}_${voucher.id}.pdf")
            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$docTitle - ${voucher.voucherNo}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "$docTitle for ${voucher.partyName} of total amount ${IndianFormatter.formatRupee(voucher.totalAmount)}" +
                        if (user.businessName.isNotBlank()) " from ${user.businessName}" else ""
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Print / Share / Email A4 PDF Invoice")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error generating PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        } finally {
            // Was closed inside the try, after the write. Any throw from the write skipped
            // it and leaked the document's native page memory on every failed export —
            // which, before the fix above, was every export.
            pdfDocument.close()
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
