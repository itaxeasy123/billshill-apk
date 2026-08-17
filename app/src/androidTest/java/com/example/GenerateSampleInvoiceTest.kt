package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.invoice.InvoiceAssembler
import com.example.invoice.InvoiceBranding
import com.example.invoice.InvoiceDocument
import com.example.invoice.InvoicePdfRenderer
import com.example.utils.GstCalculationService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the shared invoice template to real PDFs so the layout can be looked at, and
 * asserts the things a look cannot check.
 *
 * Both a fixture producer — the OCR-side parser has to stay in step with the template, and
 * regenerating these is how you check that after a layout change — and the only place the
 * renderer runs end to end. It cannot be a JVM test: android.graphics.pdf.PdfDocument is
 * backed by native pdfium, which Robolectric does not load.
 *
 * Renders straight to a file. The generator this replaced could only produce a PDF as a
 * side effect of launching a share sheet, so a fixture run put a chooser on screen and then
 * fished the newest file out of the cache directory.
 *
 * Output: the app's external files dir, under "samples".
 */
@RunWith(AndroidJUnit4::class)
class GenerateSampleInvoiceTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

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
            "Loading and unloading charges"
        )
        val units = listOf("Nos", "Kg", "Lot", "Bag", "Lot")
        val hsn = listOf("7214", "7217", "9965", "2523", "9967")
        return (0 until count).map { i ->
            val share = 1.0 / count
            val qty = (i + 1) * 3.0
            InvoiceDocument.Line(
                description = names[i % names.size],
                hsnCode = hsn[i % hsn.size],
                quantity = qty,
                unit = units[i % units.size],
                rate = taxable * share / qty,
                taxable = taxable * share,
                gstRate = 18.0,
                taxAmount = v.gstAmount * share,
                amount = (taxable + v.gstAmount) * share
            )
        }
    }

    private fun outDir(): File =
        File(ctx.getExternalFilesDir(null), "samples").apply { mkdirs() }

    private fun render(
        name: String,
        v: VoucherEntity,
        count: Int,
        branding: InvoiceBranding
    ): File {
        val doc = InvoiceAssembler.applyBranding(
            InvoiceAssembler.build(v, user, lines(v, count)),
            branding
        )
        return InvoicePdfRenderer.writePdf(ctx, doc, branding, File(outDir(), "$name.pdf"))
    }

    @Test
    fun writeSampleInvoices() {
        val default = InvoiceBranding(terms = "Payment due within 30 days of invoice date.")

        render("01-tax-invoice", voucher("INV/2026-27/1001", "Anand Traders", 11_800.0, 1_800.0), 3, default)
        render(
            "02-credit-note",
            voucher("SRN/2026-27/7001", "Anand Traders", 2_360.0, 360.0, type = VoucherType.SALES_RETURN),
            2, default
        )
        render(
            "03-debit-note",
            voucher("PRN/2026-27/8001", "Mehta Exports", 1_180.0, 180.0, type = VoucherType.PURCHASE_RETURN),
            1, default
        )
        render(
            "04-receipt",
            voucher("REC/2026-27/3001", "Anand Traders", 10_000.0, 0.0, type = VoucherType.RECEIPT),
            1, default
        )
        render(
            "05-interstate",
            voucher("INV/2026-27/1002", "Mehta Exports", 59_000.0, 9_000.0, interstate = true),
            4, default
        )
        // Odd paise: the case the whole rounding remediation was written for.
        render("06-odd-paise", voucher("INV/2026-27/1003", "M/s Sharma & Co", 1_180.06, 180.01), 2, default)
        render(
            "07-accent-slate",
            voucher("INV/2026-27/1004", "Sharma & Co", 11_800.0, 1_800.0),
            3,
            default.copy(accentArgb = InvoiceBranding.PRESET_ACCENTS[1].second)
        )
        render(
            "08-renamed-no-logo",
            voucher("INV/2026-27/1005", "Sharma & Co", 11_800.0, 1_800.0),
            3,
            default.copy(
                logo = InvoiceBranding.LogoChoice.NONE,
                companyNameOverride = "Renamed Trading Co",
                titleOverride = "PROFORMA INVOICE",
                accentArgb = InvoiceBranding.PRESET_ACCENTS[4].second
            )
        )

        val written = outDir().listFiles()?.sortedBy { it.name }.orEmpty()
        println("INVOICE SAMPLES: ${outDir().absolutePath}")
        written.forEach { println("  ${it.name} (${it.length()} bytes)") }
        assertTrue("expected 8 samples, got ${written.size}", written.size >= 8)
        assertTrue("a sample came out empty", written.all { it.length() > 1_000 })
    }

    /**
     * The template this replaced drew its rows from a loop onto a page count fixed at one,
     * so any row past the bottom margin was silently dropped from the document.
     */
    @Test
    fun longInvoicePaginatesInsteadOfDroppingRows() {
        val branding = InvoiceBranding()
        val v = voucher("INV/2026-27/1099", "Bulk Buyer Industries", 590_000.0, 90_000.0)
        val file = render("09-forty-lines", v, 40, branding)

        val doc = InvoiceAssembler.applyBranding(
            InvoiceAssembler.build(v, user, lines(v, 40)),
            branding
        )
        val pages = InvoicePdfRenderer.pageCount(ctx, doc, branding)
        println("40-line invoice: $pages page(s), ${file.length()} bytes")
        assertTrue("40 lines should need more than one page, got $pages", pages > 1)
        assertTrue("single-line invoice should be one page",
            InvoicePdfRenderer.pageCount(
                ctx,
                InvoiceAssembler.applyBranding(InvoiceAssembler.build(v, user, lines(v, 1)), branding),
                branding
            ) == 1
        )
    }
}
