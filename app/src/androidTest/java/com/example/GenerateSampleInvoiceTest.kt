package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes real BillShield invoice PDFs to external files so a parser can be developed and
 * tested against the actual generator output rather than a hand-written approximation.
 *
 * Not a test of behaviour — a fixture producer. Kept because the OCR-side parser has to
 * stay in step with PdfInvoiceGenerator, and regenerating the samples is how you check
 * that after changing the invoice layout.
 */
@RunWith(AndroidJUnit4::class)
class GenerateSampleInvoiceTest {

    private val user = UserEntity(
        id = "primary_user",
        phoneNumber = "9876543210",
        token = "",
        businessName = "Test Traders",
        gstin = "07AABCU9603R1ZM",
        firstName = "Anand",
        surname = "Kumar",
        address = "Shop 4, Connaught Place",
        city = "New Delhi",
        state = "Delhi",
        upiId = "testtraders@okhdfcbank"
    )

    private fun voucher(
        no: String, party: String, total: Double, gst: Double, interstate: Boolean
    ) = VoucherEntity(
        id = 1L, voucherNo = no, voucherType = VoucherType.SALES, date = 1_776_000_000_000L,
        partyName = party, totalAmount = total, gstAmount = gst,
        isInterstate = interstate, narration = "Steel rod supply"
    )

    @Test
    fun writeSampleInvoices() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = File(ctx.getExternalFilesDir(null), "samples").apply { mkdirs() }

        val cases = listOf(
            Triple("intrastate", voucher("INV/2026-27/1001", "Anand Traders", 11_800.0, 1_800.0, false), user),
            Triple("interstate", voucher("INV/2026-27/1002", "Mehta Exports", 59_000.0, 9_000.0, true), user),
            // Odd paise: the case the whole rounding remediation was written for.
            Triple("odd-paise", voucher("INV/2026-27/1003", "M/s Sharma & Co", 1_180.06, 180.01, false), user),
            Triple("zero-gst", voucher("INV/2026-27/1004", "Exempt Buyer", 5_000.0, 0.0, false), user)
        )

        cases.forEach { (label, v, u) ->
            com.example.utils.PdfInvoiceGenerator.generateAndSharePdf(ctx, v, u)
            val src = File(ctx.cacheDir, "invoice_pdfs")
                .listFiles()?.maxByOrNull { it.lastModified() }
            src?.copyTo(File(out, "billshield-$label.pdf"), overwrite = true)
        }

        println("SAMPLES WRITTEN TO: ${out.absolutePath}")
        out.listFiles()?.forEach { println("  ${it.name} (${it.length()} bytes)") }
    }
}
