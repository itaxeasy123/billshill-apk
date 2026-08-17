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
import java.io.File
import java.io.FileOutputStream

object LedgerStatementPdf {

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
}
