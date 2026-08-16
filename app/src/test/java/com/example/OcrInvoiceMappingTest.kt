package com.example

import com.example.data.model.VoucherType
import com.example.data.ocr.InvoiceImportResult
import com.example.data.ocr.OcrInvoiceClient
import com.example.data.ocr.OcrInvoiceData
import com.example.data.ocr.OcrInvoiceMeta
import com.example.data.ocr.OcrInvoiceRef
import com.example.data.ocr.OcrInvoiceTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary between an extracted invoice and the books.
 *
 * These payloads are the real responses from POST /api/billshield-invoice, captured
 * against PDFs this app generated. The guards below are the point of the class: a partial
 * extraction that posts is worse than one that refuses and says why, because the voucher
 * balances either way and nothing downstream would ever catch it.
 */
class OcrInvoiceMappingTest {

    private fun data(
        party: String? = "Anand Traders",
        taxable: Double? = 10_000.0,
        gst: Double? = 1_800.0,
        total: Double? = 11_800.0,
        rate: Double? = 18.0,
        interstate: Boolean? = false,
        type: String? = "SALES",
        reconciles: Boolean? = true,
        notes: String? = "Steel rod supply",
        number: String? = "INV/2026-27/1001"
    ) = OcrInvoiceData(
        party_name = party,
        invoice = OcrInvoiceRef(number = number, date = "2026-04-12"),
        totals = OcrInvoiceTotals(taxable, gst, total, 900.0, 900.0, null),
        gst_rate = rate,
        is_interstate = interstate,
        voucher_type = type,
        notes = notes,
        meta = OcrInvoiceMeta("billshield_text_layer", "billshield", reconciles)
    )

    @Test
    fun `a real intrastate invoice maps to the form`() {
        val r = OcrInvoiceClient.toResult(data()) as InvoiceImportResult.Extracted

        assertEquals("Anand Traders", r.partyName)
        assertEquals(11_800.0, r.amountInclusive, 0.0)
        assertEquals(18.0, r.gstRate, 0.0)
        assertEquals(false, r.isInterstate)
        assertEquals(VoucherType.SALES, r.voucherType)
        assertEquals("INV/2026-27/1001", r.invoiceNo)
    }

    @Test
    fun `the amount carried is the GST-INCLUSIVE total`() {
        // Not the taxable value. The wizard defaults to Exclusive, so handing it the
        // taxable value — or letting a consumer treat this as exclusive — grosses an
        // Rs 11,800 invoice up a second time to Rs 13,924.
        val r = OcrInvoiceClient.toResult(data()) as InvoiceImportResult.Extracted
        assertEquals(11_800.0, r.amountInclusive, 0.0)
    }

    @Test
    fun `an interstate invoice carries the flag`() {
        val r = OcrInvoiceClient.toResult(
            data(interstate = true, taxable = 50_000.0, gst = 9_000.0, total = 59_000.0)
        ) as InvoiceImportResult.Extracted
        assertTrue(r.isInterstate)
    }

    @Test
    fun `a zero-rated invoice is accepted, because 0 is a real slab`() {
        val r = OcrInvoiceClient.toResult(
            data(gst = 0.0, total = 5_000.0, taxable = 5_000.0, rate = 0.0)
        )
        assertTrue("0% is exempt, not a failure", r is InvoiceImportResult.Extracted)
    }

    // ---- the guards ------------------------------------------------------------------

    @Test
    fun `figures that do not add up are refused`() {
        // The server reports reconciliation: taxable + gst == total, and the heads sum to
        // the gst. A parse that fails it has misread something.
        val r = OcrInvoiceClient.toResult(data(reconciles = false))
        assertTrue(r is InvoiceImportResult.Failed)
        assertTrue((r as InvoiceImportResult.Failed).reason.contains("do not add up"))
    }

    @Test
    fun `an off-slab rate is refused here, not at save time`() {
        // addVoucher compares against the slab list by EXACT equality, so 18.0637 would be
        // rejected later with a message that no longer names the cause.
        val r = OcrInvoiceClient.toResult(data(rate = 18.0637))
        assertTrue(r is InvoiceImportResult.Failed)
        assertTrue((r as InvoiceImportResult.Failed).reason.contains("not a GST slab"))
    }

    @Test
    fun `a missing party is refused`() {
        assertTrue(OcrInvoiceClient.toResult(data(party = null)) is InvoiceImportResult.Failed)
        assertTrue(OcrInvoiceClient.toResult(data(party = "   ")) is InvoiceImportResult.Failed)
    }

    @Test
    fun `a missing or non-positive total is refused`() {
        assertTrue(OcrInvoiceClient.toResult(data(total = null)) is InvoiceImportResult.Failed)
        assertTrue(OcrInvoiceClient.toResult(data(total = 0.0)) is InvoiceImportResult.Failed)
        assertTrue(OcrInvoiceClient.toResult(data(total = -5.0)) is InvoiceImportResult.Failed)
    }

    @Test
    fun `a document that is not a sale or purchase is refused`() {
        // The generator prints "ACCOUNTING VOUCHER" for returns, journals and contras, so
        // they come back unrecognised — which must not be quietly coerced to PURCHASE.
        assertTrue(OcrInvoiceClient.toResult(data(type = null)) is InvoiceImportResult.Failed)
        assertTrue(OcrInvoiceClient.toResult(data(type = "CONTRA")) is InvoiceImportResult.Failed)
        assertTrue(OcrInvoiceClient.toResult(data(type = "JOURNAL")) is InvoiceImportResult.Failed)
    }

    @Test
    fun `a purchase invoice maps to PURCHASE`() {
        val r = OcrInvoiceClient.toResult(data(type = "PURCHASE")) as InvoiceImportResult.Extracted
        assertEquals(VoucherType.PURCHASE, r.voucherType)
    }

    @Test
    fun `a non-finite rate cannot slip through`() {
        assertTrue(
            OcrInvoiceClient.toResult(data(rate = Double.NaN)) is InvoiceImportResult.Failed
        )
        assertTrue(
            OcrInvoiceClient.toResult(data(rate = Double.POSITIVE_INFINITY))
                is InvoiceImportResult.Failed
        )
    }

    @Test
    fun `every failure explains itself`() {
        // The user has to know whether to retry, fix the invoice, or type it in.
        listOf(
            data(party = null), data(total = null), data(reconciles = false),
            data(rate = 99.9), data(type = "CONTRA")
        ).forEach {
            val r = OcrInvoiceClient.toResult(it) as InvoiceImportResult.Failed
            assertTrue("reason must be a sentence, was '${r.reason}'", r.reason.length > 20)
        }
    }
}
