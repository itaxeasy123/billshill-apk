package com.example

import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.invoice.InvoiceAssembler
import com.example.invoice.InvoiceBranding
import com.example.invoice.InvoiceDocument
import com.example.utils.GstCalculationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assembly rules of the shared invoice template, checked without a device.
 *
 * Rendering lives in GenerateSampleInvoiceTest instead: android.graphics.pdf.PdfDocument is
 * backed by native pdfium, which Robolectric does not load, so a PdfDocument constructed
 * here is born closed and startPage throws immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InvoiceTemplateRenderTest {

    private val user = UserEntity(
        id = "primary_user",
        phoneNumber = "9876543210",
        token = "",
        businessName = "Test Traders Private Limited",
        gstin = "07AABCU9603R1ZM",
        firstName = "Anand",
        surname = "Kumar",
        address = "Shop 4, Connaught Place",
        city = "New Delhi",
        state = "Delhi",
        pincode = "110001",
        email = "billing@testtraders.in",
        upiId = "testtraders@okhdfcbank"
    )

    private fun voucher(
        no: String,
        party: String,
        total: Double,
        gst: Double,
        interstate: Boolean = false,
        type: VoucherType = VoucherType.SALES
    ) = VoucherEntity(
        id = 1L, voucherNo = no, voucherType = type, date = 1_776_000_000_000L,
        partyName = party, totalAmount = total, gstAmount = gst,
        isInterstate = interstate, narration = "Supply as per purchase order PO-4471"
    )

    private fun lines(v: VoucherEntity, count: Int): List<InvoiceDocument.Line> {
        val taxable = GstCalculationService.taxableValueOf(v.totalAmount, v.gstAmount)
        val names = listOf(
            "TMT Steel Rod 12mm Fe500D grade, IS 1786 certified",
            "Binding wire 18 gauge",
            "Freight and handling",
            "Cement OPC 53 grade",
            "Loading charges"
        )
        return (0 until count).map { i ->
            val share = 1.0 / count
            InvoiceDocument.Line(
                description = names[i % names.size],
                hsnCode = listOf("7214", "7217", "9965", "2523", "9967")[i % 5],
                quantity = (i + 1) * 3.0,
                unit = listOf("Nos", "Kg", "Lot", "Bag", "Lot")[i % 5],
                rate = taxable * share / ((i + 1) * 3.0),
                taxable = taxable * share,
                gstRate = 18.0,
                taxAmount = v.gstAmount * share,
                amount = (taxable + v.gstAmount) * share
            )
        }
    }

    /** A voucher type with no tax must not print empty HSN/Taxable/GST columns. */
    @Test
    fun `untaxed documents drop the tax columns`() {
        val receipt = voucher("REC/1", "Payer", 10_000.0, 0.0, type = VoucherType.RECEIPT)
        val doc = InvoiceAssembler.build(receipt, user, lines(receipt, 1))
        assertTrue("a receipt should carry no tax rows", doc.taxRows.isEmpty())
        assertEquals(
            com.example.invoice.InvoiceColumn.UNTAXED,
            com.example.invoice.InvoiceColumn.forDocument(doc)
        )
    }

    /** Every type gets its own heading; none may fall through to a generic one. */
    @Test
    fun `every voucher type has its own document heading`() {
        val titles = VoucherType.entries.map { InvoiceAssembler.titleFor(it) }
        assertEquals("headings must be distinct", titles.size, titles.toSet().size)
        assertEquals("CREDIT NOTE", InvoiceAssembler.titleFor(VoucherType.SALES_RETURN))
        assertEquals("DEBIT NOTE", InvoiceAssembler.titleFor(VoucherType.PURCHASE_RETURN))
    }

    /**
     * Clearing an override must revert to the assembled default rather than leaving the
     * previous override in place with nothing left to overwrite it.
     *
     * Both applications start from `base`, which is the contract: styling is a fold over
     * the unstyled document, never over an already-styled one. Re-applying to a styled
     * document would treat the previous override as the default and make clearing a field
     * a no-op — the editor therefore keeps the base document and re-folds on every edit.
     */
    @Test
    fun `cleared overrides revert to the assembled defaults`() {
        val v = voucher("INV/1", "Party", 1_180.0, 180.0)
        val base = InvoiceAssembler.build(v, user, lines(v, 1))

        val styled = InvoiceAssembler.applyBranding(
            base,
            InvoiceBranding(companyNameOverride = "Renamed Co", titleOverride = "PROFORMA")
        )
        assertEquals("Renamed Co", styled.seller.name)
        assertEquals("PROFORMA", styled.title)
        assertEquals("For Renamed Co", styled.signatoryLine)

        val cleared = InvoiceAssembler.applyBranding(base, InvoiceBranding())
        assertEquals(user.businessName, cleared.seller.name)
        assertEquals("TAX INVOICE", cleared.title)
    }
}
