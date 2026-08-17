package com.example.invoice

import com.example.data.dao.AccountingDao
import com.example.data.model.GstTaxDetailEntity
import com.example.data.model.LedgerEntity
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherItemEntity
import com.example.data.model.VoucherType
import com.example.utils.GstCalculationService
import com.example.utils.IndianFormatter

/**
 * The one description of a printable business document in this app.
 *
 * Before this existed the same invoice was laid out from scratch in four places — the PDF
 * canvas, the on-screen preview, a plain-text share body and the ledger statement's
 * letterhead — and they had already drifted: the preview showed Place of Supply, Terms and
 * a UPI block the PDF did not, while the PDF carried the signature bitmap, the father's
 * name and a document-type title the preview did not. Adding one field meant editing three
 * layouts and a fifth copy of the letterhead in TallyStatementViews.
 *
 * Everything here is plain data. No android.graphics, no Compose, no resources — so the
 * PDF renderer, the Compose preview and the text share body all consume exactly the same
 * assembled document, and the assembly is unit-testable without a device.
 *
 * Build one with [InvoiceAssembler.assemble].
 */
data class InvoiceDocument(
    val title: String,
    val docNo: String,
    /** What [docNo] is called on this document type — a receipt has no "Invoice No.". */
    val docNoLabel: String,
    val dateMillis: Long,
    val seller: PartyBlock,
    val buyer: PartyBlock,
    val buyerLabel: String,
    val lines: List<Line>,
    val taxRows: List<TaxRow>,
    val subtotal: Double,
    val taxTotal: Double,
    val total: Double,
    val amountInWords: String,
    val placeOfSupply: String,
    val paymentMode: String,
    val notes: String,
    val terms: String,
    val upiId: String,
    val footerNote: String,
    val signatoryLine: String
) {
    /** One party's identity as it prints. Blank fields are dropped by [detailLines]. */
    data class PartyBlock(
        val name: String,
        val gstin: String = "",
        val address: String = "",
        val cityStatePin: String = "",
        val phone: String = "",
        val email: String = ""
    ) {
        /**
         * The contact lines that actually have content.
         *
         * Every profile field defaults blank on purpose (see UserEntity) because invented
         * defaults once leaked onto real tax invoices. A half-filled profile must not be
         * able to print as a complete registered identity, so an unset field prints
         * nothing at all rather than a label with an empty value after it.
         */
        fun detailLines(): List<String> = listOfNotNull(
            gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" },
            address.takeIf { it.isNotBlank() },
            cityStatePin.takeIf { it.isNotBlank() },
            phone.takeIf { it.isNotBlank() },
            email.takeIf { it.isNotBlank() }
        )

        val hasAnyDetail: Boolean get() = detailLines().isNotEmpty()
    }

    /**
     * One row of the item table.
     *
     * [taxable] is the pre-tax value of the line and [amount] the tax-inclusive one, so a
     * renderer never has to work out which of the two a column wants.
     */
    data class Line(
        val description: String,
        val hsnCode: String = "",
        val quantity: Double = 0.0,
        val unit: String = "",
        val rate: Double = 0.0,
        val taxable: Double,
        val gstRate: Double = 0.0,
        val taxAmount: Double = 0.0,
        val amount: Double
    ) {
        /** "2 Pcs" — empty when the line carries no quantity, as service lines do. */
        val quantityLabel: String
            get() = if (quantity <= 0.0) "" else buildString {
                append(IndianFormatter.formatQuantity(quantity))
                if (unit.isNotBlank()) {
                    append(' ')
                    append(unit)
                }
            }
    }

    /** A statutory tax head as it appears in the totals stack, e.g. "CGST @ 9%". */
    data class TaxRow(val label: String, val amount: Double)
}

/**
 * Turns a posted voucher into an [InvoiceDocument].
 *
 * This is the only place a voucher becomes a document, so the document-type title, the
 * party label and the tax split are decided once instead of per renderer.
 */
object InvoiceAssembler {

    /**
     * The printed name of each document type.
     *
     * The renderer this replaces mapped only four of the nine types and fell through to
     * "ACCOUNTING VOUCHER" for the rest — so a Credit Note, a Debit Note, a Contra and a
     * Journal all printed under the same generic heading, and a customer receiving a
     * credit note had nothing on the page saying so. All nine are named here.
     */
    fun titleFor(type: VoucherType): String = when (type) {
        VoucherType.SALES -> "TAX INVOICE"
        VoucherType.PURCHASE -> "PURCHASE INVOICE"
        VoucherType.SALES_RETURN -> "CREDIT NOTE"
        VoucherType.PURCHASE_RETURN -> "DEBIT NOTE"
        VoucherType.RECEIPT -> "RECEIPT VOUCHER"
        VoucherType.PAYMENT -> "PAYMENT VOUCHER"
        VoucherType.JOURNAL -> "JOURNAL VOUCHER"
        VoucherType.CONTRA -> "CONTRA VOUCHER"
        VoucherType.STOCK_OPENING -> "OPENING STOCK"
    }

    /** What the counterparty is called on this document type. */
    fun buyerLabelFor(type: VoucherType): String = when (type) {
        VoucherType.SALES, VoucherType.SALES_RETURN -> "Bill To"
        VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN -> "Supplier"
        VoucherType.RECEIPT -> "Received From"
        VoucherType.PAYMENT -> "Paid To"
        else -> "Party"
    }

    /** What the document number is called on this type. */
    fun docNoLabelFor(type: VoucherType): String = when (type) {
        VoucherType.SALES, VoucherType.PURCHASE -> "Invoice No."
        VoucherType.SALES_RETURN, VoucherType.PURCHASE_RETURN -> "Note No."
        else -> "Voucher No."
    }

    /** True for the types that carry GST and therefore print a tax breakdown. */
    fun carriesTax(type: VoucherType): Boolean = when (type) {
        VoucherType.SALES, VoucherType.PURCHASE,
        VoucherType.SALES_RETURN, VoucherType.PURCHASE_RETURN -> true
        else -> false
    }

    /**
     * Assembles the document for [voucher].
     *
     * Reads the real `voucher_items` rows. Both previous renderers invented a single line
     * from the voucher header instead, so quantity, rate, unit and the HSN code were
     * stored on every voucher and printed on none of them — an HSN-less tax invoice is not
     * a compliant one. When a voucher genuinely has no item rows (a Receipt, a Payment, a
     * Journal, or a sale keyed as a bare amount) one line is still synthesised from the
     * header so the table is never empty.
     */
    suspend fun assemble(
        dao: AccountingDao,
        voucher: VoucherEntity,
        user: UserEntity
    ): InvoiceDocument {
        val items = runCatching { dao.getVoucherItemsForVoucher(voucher.id) }.getOrDefault(emptyList())
        val taxDetail = runCatching { dao.getGstTaxDetailForVoucher(voucher.id) }.getOrNull()
        val partyLedger = runCatching { dao.getLedgerByLooseName(voucher.partyName) }.getOrNull()

        val lines = if (items.isEmpty()) {
            listOf(syntheticLine(voucher))
        } else {
            items.map { item ->
                val name = runCatching { dao.getInventoryItemById(item.itemId) }.getOrNull()
                lineFrom(item, name?.name, name?.hsnCode, name?.unit, voucher)
            }
        }

        return build(voucher, user, lines, taxDetail, partyLedger)
    }

    /**
     * The pure part of assembly, split out so it can be unit-tested without a device.
     *
     * Produces the document as the stored data alone describes it. User styling is never
     * applied here — a caller layers it on afterwards with [applyBranding], so that
     * clearing an override reverts to this default instead of leaving the previous
     * override baked in with nothing left to overwrite it.
     */
    fun build(
        voucher: VoucherEntity,
        user: UserEntity,
        lines: List<InvoiceDocument.Line>,
        taxDetail: GstTaxDetailEntity? = null,
        partyLedger: LedgerEntity? = null
    ): InvoiceDocument {
        val type = voucher.voucherType
        val taxTotal = GstCalculationService.splitForVoucher(voucher.gstAmount, voucher.isInterstate)
            .let { (c, s, i) -> c + s + i }
        val subtotal = GstCalculationService.taxableValueOf(voucher.totalAmount, voucher.gstAmount)

        val sellerName = user.businessName

        return InvoiceDocument(
            title = titleFor(type),
            docNo = voucher.voucherNo,
            docNoLabel = docNoLabelFor(type),
            dateMillis = voucher.date,
            seller = InvoiceDocument.PartyBlock(
                name = sellerName,
                gstin = user.gstin,
                address = user.address,
                cityStatePin = listOf(user.city, user.state, user.pincode)
                    .filter { it.isNotBlank() }
                    .joinToString(", "),
                phone = user.phoneNumber,
                email = user.email
            ),
            buyer = InvoiceDocument.PartyBlock(
                name = voucher.partyName,
                gstin = partyLedger?.gstin.orEmpty(),
                // LedgerEntity carries no street line — a party's address is only held as
                // city/state/pincode — so the buyer block prints those and omits the rest
                // rather than inventing a line that was never captured.
                cityStatePin = listOfNotNull(
                    partyLedger?.city?.takeIf { it.isNotBlank() },
                    partyLedger?.state?.takeIf { it.isNotBlank() },
                    partyLedger?.pincode?.takeIf { it.isNotBlank() }
                ).joinToString(", ")
            ),
            buyerLabel = buyerLabelFor(type),
            lines = lines,
            taxRows = taxRowsFor(voucher, taxDetail),
            subtotal = subtotal,
            taxTotal = taxTotal,
            total = voucher.totalAmount,
            amountInWords = IndianFormatter.convertNumberToWords(voucher.totalAmount),
            placeOfSupply = if (carriesTax(type)) {
                partyLedger?.state?.takeIf { it.isNotBlank() } ?: user.state
            } else {
                ""
            },
            paymentMode = voucher.paymentMode,
            notes = voucher.narration,
            terms = "",
            upiId = user.upiId,
            footerNote = InvoiceBranding.DEFAULT_FOOTER,
            signatoryLine = if (sellerName.isNotBlank()) "For $sellerName" else ""
        )
    }

    /**
     * Re-applies the user-editable parts of [branding] to an already-assembled document.
     *
     * Lets the invoice editor re-render on every keystroke and every colour tap without
     * going back to the database — nothing this touches is derived from a stored row.
     *
     * Always fold onto a document straight from [build], never onto one this has already
     * styled: an override reads the current value as its fallback, so re-folding would
     * treat the last override as the default and clearing a field would stop reverting it.
     *
     * It deliberately reaches only the presentation fields. Totals, tax heads, GSTIN, the
     * document number and the date are assembled from the posted voucher and cannot be
     * overlaid here: restyling your own invoice is ordinary, retyping the tax on it is
     * not. Figures are changed by editing the voucher, which re-posts the ledger with it.
     */
    fun applyBranding(doc: InvoiceDocument, branding: InvoiceBranding): InvoiceDocument {
        val name = branding.companyNameOverride.takeIf { it.isNotBlank() } ?: doc.seller.name
        return doc.copy(
            title = branding.titleOverride.takeIf { it.isNotBlank() } ?: doc.title,
            seller = doc.seller.copy(name = name),
            terms = branding.terms,
            footerNote = branding.footerNote,
            signatoryLine = if (name.isNotBlank()) "For $name" else ""
        )
    }

    /**
     * The tax heads for the totals stack.
     *
     * Rates come from the stored `gst_tax_details` row when there is one, because that is
     * what was actually posted. The amounts never do: they are always re-derived through
     * [GstCalculationService.splitForVoucher], which quantises CGST and takes SGST as the
     * residual so the two heads add back to the printed tax total exactly. Rows posted
     * before that convention existed still hold unquantised halves that would print a
     * paisa over the total they sit under.
     */
    private fun taxRowsFor(
        voucher: VoucherEntity,
        detail: GstTaxDetailEntity?
    ): List<InvoiceDocument.TaxRow> {
        if (!carriesTax(voucher.voucherType) || voucher.gstAmount <= 0.0) return emptyList()

        val (cgst, sgst, igst) = GstCalculationService.splitForVoucher(
            voucher.gstAmount,
            voucher.isInterstate
        )
        fun rate(stored: Double?): String =
            stored?.takeIf { it > 0.0 }?.let { " @ ${IndianFormatter.formatQuantity(it)}%" }.orEmpty()

        return if (voucher.isInterstate) {
            listOf(InvoiceDocument.TaxRow("IGST${rate(detail?.igstRate)}", igst))
        } else {
            listOf(
                InvoiceDocument.TaxRow("CGST${rate(detail?.cgstRate)}", cgst),
                InvoiceDocument.TaxRow("SGST${rate(detail?.sgstRate)}", sgst)
            )
        }
    }

    private fun lineFrom(
        item: VoucherItemEntity,
        name: String?,
        hsn: String?,
        unit: String?,
        voucher: VoucherEntity
    ): InvoiceDocument.Line {
        val tax = item.cgstAmount + item.sgstAmount + item.igstAmount
        val taxable = if (item.amount > 0.0) item.amount else item.quantity * item.rate
        return InvoiceDocument.Line(
            description = name?.takeIf { it.isNotBlank() }
                ?: voucher.narration.takeIf { it.isNotBlank() }
                ?: voucher.partyName,
            hsnCode = hsn.orEmpty(),
            quantity = item.quantity,
            unit = unit.orEmpty(),
            rate = item.rate,
            taxable = taxable,
            gstRate = item.gstRate,
            taxAmount = tax,
            amount = taxable + tax
        )
    }

    /** The fallback line for a voucher with no stored item rows. */
    private fun syntheticLine(voucher: VoucherEntity): InvoiceDocument.Line {
        val taxable = GstCalculationService.taxableValueOf(voucher.totalAmount, voucher.gstAmount)
        return InvoiceDocument.Line(
            description = voucher.narration.takeIf { it.isNotBlank() }
                ?: "${titleFor(voucher.voucherType)} — ${voucher.partyName}",
            taxable = taxable,
            taxAmount = voucher.gstAmount,
            amount = voucher.totalAmount
        )
    }
}
