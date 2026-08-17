package com.example.invoice

import com.example.utils.IndianFormatter

/**
 * The column structure of the item table, shared by every renderer.
 *
 * The PDF and the on-screen preview drifted apart last time because each decided its own
 * columns. Here the set of columns, their order, their widths and the value each one pulls
 * off a line are decided once; a renderer only chooses how to paint them.
 *
 * Widths are fractions of the content width and are asserted to sum to 1.
 */
enum class InvoiceColumn(
    val header: String,
    val weight: Float,
    val alignEnd: Boolean
) {
    SERIAL("#", 0.04f, false),
    DESCRIPTION("Description", 0.30f, false),
    HSN("HSN", 0.09f, false),
    QTY("Qty", 0.09f, true),
    RATE("Rate", 0.12f, true),
    TAXABLE("Taxable", 0.12f, true),
    GST("GST", 0.12f, true),
    AMOUNT("Amount", 0.12f, true);

    companion object {
        /** Columns for a document that carries GST. */
        val TAXED: List<InvoiceColumn> = listOf(
            SERIAL, DESCRIPTION, HSN, QTY, RATE, TAXABLE, GST, AMOUNT
        )

        /**
         * Columns for a Receipt, Payment, Journal or Contra.
         *
         * These carry no tax, so the HSN, Taxable and GST columns would print blank on
         * every row; the remaining width goes to the description instead.
         */
        val UNTAXED: List<InvoiceColumn> = listOf(SERIAL, DESCRIPTION, QTY, RATE, AMOUNT)

        fun forDocument(doc: InvoiceDocument): List<InvoiceColumn> =
            if (doc.taxRows.isEmpty()) UNTAXED else TAXED

        /**
         * [weight] renormalised across [columns].
         *
         * The weights are authored to sum to 1 across the full taxed set, so any subset
         * has to be rescaled or the table would not reach the right margin.
         */
        fun normalisedWeights(columns: List<InvoiceColumn>): List<Float> {
            val total = columns.map { it.weight }.sum()
            return columns.map { it.weight / total }
        }
    }

    /** This column's cell text for [line], already formatted. */
    fun cell(line: InvoiceDocument.Line, index: Int): String = when (this) {
        SERIAL -> (index + 1).toString()
        DESCRIPTION -> line.description
        HSN -> line.hsnCode
        QTY -> line.quantityLabel
        RATE -> if (line.rate > 0.0) IndianFormatter.formatRupee(line.rate) else ""
        TAXABLE -> IndianFormatter.formatRupee(line.taxable)
        GST -> if (line.taxAmount > 0.0) IndianFormatter.formatRupee(line.taxAmount) else "—"
        AMOUNT -> IndianFormatter.formatRupee(line.amount)
    }
}

/**
 * Page geometry for the A4 PDF, in PostScript points.
 *
 * Kept beside the column model so the preview can mirror the same proportions.
 */
object InvoicePageSpec {
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 34f

    const val BANNER_HEIGHT = 118f
    const val FOOTER_HEIGHT = 42f

    val contentWidth: Float get() = PAGE_WIDTH - (MARGIN * 2)

    /** The lowest y a table row may occupy before the page must break. */
    val rowFloor: Float get() = PAGE_HEIGHT - FOOTER_HEIGHT - 24f
}
