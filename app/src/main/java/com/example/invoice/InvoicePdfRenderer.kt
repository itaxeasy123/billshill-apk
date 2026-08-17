package com.example.invoice

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import androidx.core.content.ContextCompat
import com.example.R
import com.example.utils.IndianFormatter
import java.io.File
import java.io.FileOutputStream

/**
 * Draws an [InvoiceDocument] onto A4 pages.
 *
 * This is the only invoice layout in the app. The previous one lived at hardcoded pixel
 * coordinates inside a single 200-line function, could not paginate — a voucher with more
 * rows than fitted simply lost them off the bottom of page one — and existed in a second,
 * separately-maintained copy for ledger statements.
 *
 * Everything colour-bearing comes from [InvoiceBranding.accentArgb], so changing the
 * user's accent restyles the banner, the table rule, the totals band and the footer
 * together and cannot leave one element on last month's colour.
 */
object InvoicePdfRenderer {

    private const val ROW_V_PADDING = 7f
    private const val MIN_ROW_HEIGHT = 22f
    private const val DESCRIPTION_MAX_LINES = 3

    private val serifBold get() = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sans get() = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val sansBold get() = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** Writes [doc] to [outFile] and returns it. */
    fun writePdf(
        context: Context,
        doc: InvoiceDocument,
        branding: InvoiceBranding,
        outFile: File
    ): File {
        val pdf = PdfDocument()
        try {
            paginate(context, pdf, doc, branding)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            // Closed in a finally because a throw from the write used to skip it and leak
            // the document's native page memory on every failed export.
            pdf.close()
        }
        return outFile
    }

    /** How many pages [doc] needs. Used by the print adapter, which must declare a count. */
    fun pageCount(context: Context, doc: InvoiceDocument, branding: InvoiceBranding): Int {
        val pdf = PdfDocument()
        return try {
            paginate(context, pdf, doc, branding)
            pdf.pages.size.coerceAtLeast(1)
        } catch (e: Exception) {
            1
        } finally {
            pdf.close()
        }
    }

    private fun paginate(
        context: Context,
        pdf: PdfDocument,
        doc: InvoiceDocument,
        branding: InvoiceBranding
    ) {
        val accent = branding.accentArgb
        val columns = InvoiceColumn.forDocument(doc)
        val weights = InvoiceColumn.normalisedWeights(columns)
        val bounds = columnBounds(weights)

        var pageNo = 1
        var page = pdf.startPage(pageInfo(pageNo))
        var canvas = page.canvas

        var y = drawBanner(context, canvas, doc, branding)
        y = drawMetaBlock(canvas, doc, y, accent)
        y = drawTableHeader(canvas, columns, bounds, y, accent)

        doc.lines.forEachIndexed { index, line ->
            val height = rowHeight(line, columns, bounds)
            if (y + height > InvoicePageSpec.rowFloor) {
                drawFooter(canvas, doc, accent, pageNo)
                pdf.finishPage(page)
                pageNo++
                page = pdf.startPage(pageInfo(pageNo))
                canvas = page.canvas
                y = InvoicePageSpec.MARGIN + 8f
                y = drawTableHeader(canvas, columns, bounds, y, accent)
            }
            y = drawRow(canvas, line, index, columns, bounds, y, height)
        }

        // The summary block must not be orphaned onto a page of its own with no context,
        // so it moves as a unit if it will not fit under the last row.
        val summaryHeight = summaryHeight(doc)
        if (y + summaryHeight > InvoicePageSpec.rowFloor) {
            drawFooter(canvas, doc, accent, pageNo)
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(pageInfo(pageNo))
            canvas = page.canvas
            y = InvoicePageSpec.MARGIN + 8f
        }
        drawSummary(context, canvas, doc, y, accent)
        drawFooter(canvas, doc, accent, pageNo)
        pdf.finishPage(page)
    }

    private fun pageInfo(pageNo: Int) = PdfDocument.PageInfo.Builder(
        InvoicePageSpec.PAGE_WIDTH.toInt(),
        InvoicePageSpec.PAGE_HEIGHT.toInt(),
        pageNo
    ).create()

    // ---------------------------------------------------------------- banner

    /**
     * The accent banner: mark and document title on the left, seller identity on the
     * right. Returns the y to continue from.
     */
    private fun drawBanner(
        context: Context,
        canvas: Canvas,
        doc: InvoiceDocument,
        branding: InvoiceBranding
    ): Float {
        val h = InvoicePageSpec.BANNER_HEIGHT
        canvas.drawRect(
            0f, 0f, InvoicePageSpec.PAGE_WIDTH, h,
            Paint().apply { color = branding.accentArgb; isAntiAlias = true }
        )

        val left = InvoicePageSpec.MARGIN
        val right = InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN

        // The mark sits on a white plate. The iTaxEasy logo is itself blue, so on a blue
        // banner — the default accent — it would be invisible without one, and a
        // user-chosen accent could collide with any custom logo the same way.
        val logoBottom = drawLogo(context, canvas, branding, left, 16f, 34f)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            typeface = serifBold
            textSize = 30f
            isAntiAlias = true
        }
        canvas.drawText(doc.title, left, (logoBottom + 40f).coerceAtMost(h - 14f), titlePaint)

        val namePaint = Paint().apply {
            color = Color.WHITE
            typeface = sansBold
            textSize = 13f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        val detailPaint = Paint().apply {
            color = Color.argb(225, 255, 255, 255)
            typeface = sans
            textSize = 8.5f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        var ty = 30f
        if (doc.seller.name.isNotBlank()) {
            canvas.drawText(fit(doc.seller.name, namePaint, 300f), right, ty, namePaint)
            ty += 15f
        }
        doc.seller.detailLines().take(5).forEach { line ->
            canvas.drawText(fit(line, detailPaint, 300f), right, ty, detailPaint)
            ty += 11f
        }
        return h + 26f
    }

    /**
     * Draws the mark inside a white plate and returns the plate's bottom edge.
     *
     * Returns [top] unchanged when there is no mark to draw, so the title moves up to
     * where the plate would have been rather than leaving a gap.
     */
    private fun drawLogo(
        context: Context,
        canvas: Canvas,
        branding: InvoiceBranding,
        left: Float,
        top: Float,
        height: Float
    ): Float {
        val pad = 5f
        when (branding.effectiveLogo()) {
            InvoiceBranding.LogoChoice.NONE -> return top

            InvoiceBranding.LogoChoice.ITAXEASY -> {
                val drawable: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_itaxeasy_logo)
                    ?: return top
                val ratio = drawable.intrinsicWidth.toFloat() /
                    drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
                val w = height * ratio
                plate(canvas, left, top, w + pad * 2, height + pad * 2)
                drawable.setBounds(
                    (left + pad).toInt(),
                    (top + pad).toInt(),
                    (left + pad + w).toInt(),
                    (top + pad + height).toInt()
                )
                drawable.draw(canvas)
                return top + height + pad * 2
            }

            InvoiceBranding.LogoChoice.CUSTOM -> {
                val file = branding.resolvedCustomLogo() ?: return top
                val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                    ?: return top
                val ratio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                // Clamped so a very wide logo cannot run under the seller block.
                val w = (height * ratio).coerceAtMost(190f)
                plate(canvas, left, top, w + pad * 2, height + pad * 2)
                canvas.drawBitmap(
                    bmp,
                    null,
                    RectF(left + pad, top + pad, left + pad + w, top + pad + height),
                    Paint().apply { isFilterBitmap = true; isAntiAlias = true }
                )
                return top + height + pad * 2
            }
        }
    }

    private fun plate(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRoundRect(
            RectF(x, y, x + w, y + h), 4f, 4f,
            Paint().apply { color = Color.WHITE; isAntiAlias = true }
        )
    }

    // ------------------------------------------------------------ meta block

    /** Document number and date on the left, the counterparty on the right. */
    private fun drawMetaBlock(
        canvas: Canvas,
        doc: InvoiceDocument,
        top: Float,
        accent: Int
    ): Float {
        val left = InvoicePageSpec.MARGIN
        val right = InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN

        val label = Paint().apply {
            color = Color.rgb(90, 90, 96); typeface = sansBold; textSize = 9f; isAntiAlias = true
        }
        val value = Paint().apply {
            color = Color.rgb(20, 20, 24); typeface = sans; textSize = 10f; isAntiAlias = true
        }
        val heading = Paint().apply {
            color = accent; typeface = sansBold; textSize = 10f; isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        val partyName = Paint().apply {
            color = Color.rgb(20, 20, 24); typeface = sansBold; textSize = 11.5f
            isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }
        val partyDetail = Paint().apply {
            color = Color.rgb(85, 85, 92); typeface = sans; textSize = 9f
            isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }

        var ly = top
        canvas.drawText(doc.docNoLabel, left, ly, label)
        canvas.drawText(doc.docNo.ifBlank { "—" }, left + 78f, ly, value)
        ly += 15f
        canvas.drawText("Date of Issue", left, ly, label)
        canvas.drawText(IndianFormatter.formatDate(doc.dateMillis), left + 78f, ly, value)
        ly += 15f
        if (doc.placeOfSupply.isNotBlank()) {
            canvas.drawText("Place of Supply", left, ly, label)
            canvas.drawText(fit(doc.placeOfSupply, value, 150f), left + 78f, ly, value)
            ly += 15f
        }
        if (doc.paymentMode.isNotBlank()) {
            canvas.drawText("Payment Mode", left, ly, label)
            canvas.drawText(doc.paymentMode, left + 78f, ly, value)
            ly += 15f
        }

        var ry = top
        canvas.drawText(doc.buyerLabel, right, ry, heading)
        ry += 15f
        canvas.drawText(fit(doc.buyer.name.ifBlank { "—" }, partyName, 240f), right, ry, partyName)
        ry += 13f
        doc.buyer.detailLines().take(4).forEach { line ->
            canvas.drawText(fit(line, partyDetail, 240f), right, ry, partyDetail)
            ry += 11f
        }

        return maxOf(ly, ry) + 14f
    }

    // ----------------------------------------------------------------- table

    private fun columnBounds(weights: List<Float>): List<Pair<Float, Float>> {
        val startX = InvoicePageSpec.MARGIN
        val width = InvoicePageSpec.contentWidth
        var x = startX
        return weights.map { w ->
            val cw = width * w
            val pair = x to (x + cw)
            x += cw
            pair
        }
    }

    private fun drawTableHeader(
        canvas: Canvas,
        columns: List<InvoiceColumn>,
        bounds: List<Pair<Float, Float>>,
        top: Float,
        accent: Int
    ): Float {
        val left = InvoicePageSpec.MARGIN
        val right = InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN
        val h = 22f

        canvas.drawLine(left, top, right, top, rule(accent, 1.4f))
        val paint = Paint().apply {
            color = accent; typeface = sansBold; textSize = 9f; isAntiAlias = true
        }
        columns.forEachIndexed { i, col ->
            val (cs, ce) = bounds[i]
            paint.textAlign = if (col.alignEnd) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(col.header, if (col.alignEnd) ce - 4f else cs + 4f, top + 14.5f, paint)
        }
        canvas.drawLine(left, top + h, right, top + h, rule(accent, 1.4f))
        return top + h
    }

    private fun rowHeight(
        line: InvoiceDocument.Line,
        columns: List<InvoiceColumn>,
        bounds: List<Pair<Float, Float>>
    ): Float {
        val idx = columns.indexOf(InvoiceColumn.DESCRIPTION)
        if (idx < 0) return MIN_ROW_HEIGHT
        val (cs, ce) = bounds[idx]
        val paint = Paint().apply { typeface = sans; textSize = 9.5f }
        val lines = wrap(line.description, paint, ce - cs - 8f, DESCRIPTION_MAX_LINES)
        return maxOf(MIN_ROW_HEIGHT, lines.size * 11.5f + ROW_V_PADDING * 2)
    }

    private fun drawRow(
        canvas: Canvas,
        line: InvoiceDocument.Line,
        index: Int,
        columns: List<InvoiceColumn>,
        bounds: List<Pair<Float, Float>>,
        top: Float,
        height: Float
    ): Float {
        val left = InvoicePageSpec.MARGIN
        val right = InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN

        if (index % 2 == 1) {
            canvas.drawRect(
                left, top, right, top + height,
                Paint().apply { color = Color.rgb(246, 247, 250) }
            )
        }

        val text = Paint().apply {
            color = Color.rgb(28, 28, 32); typeface = sans; textSize = 9.5f; isAntiAlias = true
        }
        val amount = Paint().apply {
            color = Color.rgb(20, 20, 24); typeface = sansBold; textSize = 9.5f; isAntiAlias = true
        }

        columns.forEachIndexed { i, col ->
            val (cs, ce) = bounds[i]
            val paint = if (col == InvoiceColumn.AMOUNT) amount else text
            paint.textAlign = if (col.alignEnd) Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (col.alignEnd) ce - 4f else cs + 4f

            if (col == InvoiceColumn.DESCRIPTION) {
                wrap(line.description, paint, ce - cs - 8f, DESCRIPTION_MAX_LINES)
                    .forEachIndexed { li, part ->
                        canvas.drawText(part, x, top + ROW_V_PADDING + 9f + li * 11.5f, paint)
                    }
            } else {
                val cell = col.cell(line, index)
                if (cell.isNotBlank()) {
                    canvas.drawText(
                        fit(cell, paint, ce - cs - 8f),
                        x,
                        top + ROW_V_PADDING + 9f,
                        paint
                    )
                }
            }
        }

        canvas.drawLine(left, top + height, right, top + height, rule(Color.rgb(222, 224, 230), 0.6f))
        return top + height
    }

    // --------------------------------------------------------------- summary

    private fun summaryHeight(doc: InvoiceDocument): Float {
        val rows = 1 + doc.taxRows.size + 1
        var h = rows * 17f + 40f
        if (doc.amountInWords.isNotBlank()) h += 26f
        if (doc.terms.isNotBlank()) h += 26f
        if (doc.notes.isNotBlank()) h += 20f
        if (doc.upiId.isNotBlank()) h += 16f
        return h + 56f
    }

    /** Totals stack on the right; terms, notes and the signatory on the left. */
    private fun drawSummary(
        context: Context,
        canvas: Canvas,
        doc: InvoiceDocument,
        top: Float,
        accent: Int
    ) {
        val right = InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN
        val left = InvoicePageSpec.MARGIN
        val stackLeft = right - 210f

        val label = Paint().apply {
            color = Color.rgb(70, 70, 76); typeface = sansBold; textSize = 9.5f
            isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }
        val value = Paint().apply {
            color = Color.rgb(20, 20, 24); typeface = sans; textSize = 9.5f
            isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }

        var y = top + 18f
        canvas.drawText("Subtotal", stackLeft + 120f, y, label)
        canvas.drawText(IndianFormatter.formatRupee(doc.subtotal), right - 4f, y, value)
        y += 17f

        doc.taxRows.forEach { row ->
            canvas.drawText(row.label, stackLeft + 120f, y, label)
            canvas.drawText(IndianFormatter.formatRupee(row.amount), right - 4f, y, value)
            y += 17f
        }

        // Total band, in the accent, mirroring the banner so the eye lands on it.
        y += 4f
        canvas.drawRect(
            stackLeft, y - 13f, right, y + 12f,
            Paint().apply { color = accent; isAntiAlias = true }
        )
        canvas.drawText(
            "Total",
            stackLeft + 120f, y + 4f,
            Paint().apply {
                color = Color.WHITE; typeface = sansBold; textSize = 11f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
        )
        canvas.drawText(
            IndianFormatter.formatRupee(doc.total),
            right - 6f, y + 4f,
            Paint().apply {
                color = Color.WHITE; typeface = sansBold; textSize = 11.5f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
        )
        val stackBottom = y + 22f

        // Left column.
        val small = Paint().apply {
            color = Color.rgb(60, 60, 66); typeface = sans; textSize = 9f; isAntiAlias = true
        }
        val smallBold = Paint().apply {
            color = Color.rgb(30, 30, 36); typeface = sansBold; textSize = 9f; isAntiAlias = true
        }
        var ly = top + 18f
        val leftWidth = stackLeft - left - 16f

        if (doc.amountInWords.isNotBlank()) {
            canvas.drawText("Amount in Words", left, ly, smallBold)
            ly += 12f
            wrap(doc.amountInWords, small, leftWidth, 3).forEach {
                canvas.drawText(it, left, ly, small)
                ly += 11f
            }
            ly += 6f
        }
        if (doc.notes.isNotBlank()) {
            canvas.drawText("Notes", left, ly, smallBold)
            ly += 12f
            wrap(doc.notes, small, leftWidth, 2).forEach {
                canvas.drawText(it, left, ly, small)
                ly += 11f
            }
            ly += 6f
        }
        if (doc.terms.isNotBlank()) {
            canvas.drawText("Terms", left, ly, smallBold)
            ly += 12f
            wrap(doc.terms, small, leftWidth, 3).forEach {
                canvas.drawText(it, left, ly, small)
                ly += 11f
            }
            ly += 6f
        }
        if (doc.upiId.isNotBlank()) {
            canvas.drawText("Pay via UPI: ${doc.upiId}", left, ly, smallBold)
            ly += 14f
        }

        // Signatory, below whichever column ran longer.
        var sy = maxOf(stackBottom, ly) + 26f
        if (sy > InvoicePageSpec.rowFloor - 40f) sy = InvoicePageSpec.rowFloor - 40f
        if (doc.signatoryLine.isNotBlank()) {
            canvas.drawText(
                doc.signatoryLine, right, sy,
                Paint().apply {
                    color = Color.rgb(30, 30, 36); typeface = sansBold; textSize = 9.5f
                    isAntiAlias = true; textAlign = Paint.Align.RIGHT
                }
            )
        }
        // The signature drawn in Settings, stamped above the rule. Carried over from the
        // template this replaced, which was the only place it was ever rendered.
        InvoiceBrandingStore.signatureFile(context)?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.let { sig ->
                canvas.drawBitmap(
                    sig,
                    null,
                    RectF(right - 132f, sy + 2f, right - 12f, sy + 28f),
                    Paint().apply { isFilterBitmap = true; isAntiAlias = true }
                )
            }
        }

        canvas.drawLine(right - 150f, sy + 30f, right, sy + 30f, rule(Color.rgb(150, 150, 158), 0.8f))
        canvas.drawText(
            "Authorised Signatory", right, sy + 42f,
            Paint().apply {
                color = Color.rgb(90, 90, 96); typeface = sans; textSize = 8.5f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
        )
    }

    // ---------------------------------------------------------------- footer

    private fun drawFooter(canvas: Canvas, doc: InvoiceDocument, accent: Int, pageNo: Int) {
        val h = InvoicePageSpec.FOOTER_HEIGHT
        val topY = InvoicePageSpec.PAGE_HEIGHT - h
        canvas.drawRect(
            0f, topY, InvoicePageSpec.PAGE_WIDTH, InvoicePageSpec.PAGE_HEIGHT,
            Paint().apply { color = accent; isAntiAlias = true }
        )
        if (doc.footerNote.isNotBlank()) {
            canvas.drawText(
                doc.footerNote,
                InvoicePageSpec.PAGE_WIDTH / 2f,
                topY + 25f,
                Paint().apply {
                    color = Color.WHITE; typeface = sansBold; textSize = 10f
                    isAntiAlias = true; textAlign = Paint.Align.CENTER
                }
            )
        }
        canvas.drawText(
            "Page $pageNo",
            InvoicePageSpec.PAGE_WIDTH - InvoicePageSpec.MARGIN,
            topY + 25f,
            Paint().apply {
                color = Color.argb(200, 255, 255, 255); typeface = sans; textSize = 8f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
        )
    }

    // ---------------------------------------------------------------- text

    private fun rule(colour: Int, width: Float) = Paint().apply {
        color = colour
        strokeWidth = width
        isAntiAlias = true
    }

    /** [text] truncated with an ellipsis so it cannot overrun its column. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /**
     * Word-wraps [text] to at most [maxLines], ellipsising the last line if it overflows.
     *
     * Long descriptions used to be drawn as one unbroken run of text that simply painted
     * over the columns to its right.
     */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isBlank() || maxWidth <= 0f) return emptyList()
        val out = mutableListOf<String>()
        var current = StringBuilder()

        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current = StringBuilder(word)
                if (out.size == maxLines) return@forEach
            }
        }
        if (current.isNotEmpty() && out.size < maxLines) out += current.toString()

        return if (out.size <= maxLines) out
        else out.take(maxLines).mapIndexed { i, s ->
            if (i == maxLines - 1) fit("$s…", paint, maxWidth) else s
        }
    }
}
