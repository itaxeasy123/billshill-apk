package com.example.invoice

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.example.utils.FileNames
import com.example.utils.IndianFormatter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The single way an invoice leaves the app — as a PDF, as a print job, or as text.
 *
 * Sharing used to be re-implemented per call site, which is how one of them ended up
 * naming a plain-text file ".pdf" and producing downloads that would not open.
 */
object InvoiceExporter {

    private const val PDF_DIR = "invoice_pdfs"

    /**
     * Renders [doc] to a PDF in the cache and returns the file.
     *
     * The row id is part of the name because sanitising a voucher number is not injective
     * — the prefix is free text, so two vouchers in one book can sanitise to the same
     * stem — and overwriting one tax invoice with another while sharing it under the right
     * name is far worse than a loud failure.
     */
    fun renderPdf(
        context: Context,
        doc: InvoiceDocument,
        branding: InvoiceBranding,
        voucherId: Long
    ): File {
        val dir = File(context.cacheDir, PDF_DIR).apply { mkdirs() }
        val stem = FileNames.safe(doc.docNo, fallback = "voucher")
        val file = File(dir, "${FileNames.safe(doc.title, fallback = "Invoice")}_${stem}_$voucherId.pdf")
        return InvoicePdfRenderer.writePdf(context, doc, branding, file)
    }

    /** Opens the system share sheet for an already-rendered [pdf]. */
    fun sharePdf(context: Context, pdf: File, doc: InvoiceDocument) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${doc.title} — ${doc.docNo}")
            putExtra(Intent.EXTRA_TEXT, shareBody(doc))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(context, intent, "Share ${doc.title}")
    }

    /** Shares the document as plain text, for chat apps where a PDF is awkward. */
    fun shareText(context: Context, doc: InvoiceDocument) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${doc.title} — ${doc.docNo}")
            putExtra(Intent.EXTRA_TEXT, textSummary(doc))
        }
        startChooser(context, intent, "Share ${doc.title}")
    }

    /**
     * Starts the share sheet from the hosting Activity when there is one.
     *
     * FLAG_ACTIVITY_NEW_TASK must NOT be set for an Activity-hosted launch. The invoice is
     * shared from inside a Compose Dialog, and a chooser started in its own task lost focus
     * to the dialog's task the instant it appeared — the chooser really did launch and
     * resolve its targets, then finished in onStop before a single frame was visible, so
     * the button read as doing nothing at all. The flag is only needed when the context is
     * not an Activity, where omitting it would throw instead.
     */
    private fun startChooser(context: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        val activity = context.findActivity()
        if (activity != null) {
            activity.startActivity(chooser)
        } else {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Unwraps the ContextWrapper chain a Compose Dialog's context sits behind. */
    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Sends [pdf] to the system print spooler.
     *
     * The app had a working print adapter that nothing ever called — every "Print" button
     * opened a share sheet instead — and it declared a hardcoded page count of 1, so a
     * multi-page document would have been mis-declared had it ever run. [pageCount] is
     * passed in from the renderer that actually produced the file.
     */
    fun printPdf(context: Context, pdf: File, doc: InvoiceDocument, pageCount: Int) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = "${doc.title} ${doc.docNo}".trim()

        printManager.print(
            jobName,
            object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    callback?.onLayoutFinished(
                        PrintDocumentInfo.Builder(pdf.name)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(pageCount.coerceAtLeast(1))
                            .build(),
                        true
                    )
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        FileInputStream(pdf).use { input ->
                            FileOutputStream(destination?.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            },
            null
        )
    }

    /** A one-line summary for the body of a share. */
    private fun shareBody(doc: InvoiceDocument): String = buildString {
        append(doc.title)
        if (doc.docNo.isNotBlank()) append(" ${doc.docNo}")
        if (doc.buyer.name.isNotBlank()) append(" for ${doc.buyer.name}")
        append(" — ${IndianFormatter.formatRupee(doc.total)}")
        if (doc.seller.name.isNotBlank()) append(" from ${doc.seller.name}")
    }

    /**
     * The document as plain text.
     *
     * Built from the same [InvoiceDocument] as the PDF and the preview, so it can no
     * longer state different figures from the page it accompanies — the old text summary
     * was a fourth independent restatement of the invoice field set.
     */
    fun textSummary(doc: InvoiceDocument): String = buildString {
        appendLine(doc.title)
        if (doc.seller.name.isNotBlank()) appendLine(doc.seller.name)
        doc.seller.detailLines().forEach { appendLine(it) }
        appendLine()
        if (doc.docNo.isNotBlank()) appendLine("No: ${doc.docNo}")
        appendLine("Date: ${IndianFormatter.formatDate(doc.dateMillis)}")
        appendLine()
        appendLine("${doc.buyerLabel}: ${doc.buyer.name}")
        doc.buyer.detailLines().forEach { appendLine(it) }
        appendLine()

        doc.lines.forEachIndexed { i, line ->
            append("${i + 1}. ${line.description}")
            if (line.quantityLabel.isNotBlank()) append(" x ${line.quantityLabel}")
            appendLine("  ${IndianFormatter.formatRupee(line.amount)}")
        }
        appendLine()
        appendLine("Subtotal: ${IndianFormatter.formatRupee(doc.subtotal)}")
        doc.taxRows.forEach { appendLine("${it.label}: ${IndianFormatter.formatRupee(it.amount)}") }
        appendLine("Total: ${IndianFormatter.formatRupee(doc.total)}")
        if (doc.amountInWords.isNotBlank()) appendLine("(${doc.amountInWords})")
        if (doc.upiId.isNotBlank()) {
            appendLine()
            appendLine("Pay via UPI: ${doc.upiId}")
        }
        if (doc.terms.isNotBlank()) {
            appendLine()
            appendLine("Terms: ${doc.terms}")
        }
    }
}
