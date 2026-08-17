package com.example

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.invoice.InvoiceAssembler
import com.example.invoice.InvoiceBranding
import com.example.invoice.InvoiceDocument
import com.example.invoice.InvoicePreview
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the on-screen invoice so it can be compared against the PDF the same document
 * produces.
 *
 * The pair is the point: the preview and the PDF are two renderers over one
 * [InvoiceDocument], and the reason the old pair drifted is that neither was ever looked at
 * beside the other. The PDF side is produced by GenerateSampleInvoiceTest.
 *
 * Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w720dp-h1600dp-xhdpi")
class InvoicePreviewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val user = UserEntity(
        id = "primary_user",
        phoneNumber = "9876543210",
        token = "",
        businessName = "Test Traders Private Limited",
        gstin = "07AABCU9603R1ZM",
        address = "Shop 4, Connaught Place",
        city = "New Delhi",
        state = "Delhi",
        pincode = "110001",
        email = "billing@testtraders.in",
        upiId = "testtraders@okhdfcbank"
    )

    private fun doc(type: VoucherType, gst: Double, lineCount: Int): InvoiceDocument {
        val v = VoucherEntity(
            id = 1L,
            voucherNo = "INV/2026-27/1001",
            voucherType = type,
            date = 1_776_000_000_000L,
            partyName = "Anand Traders",
            totalAmount = 11_800.0,
            gstAmount = gst,
            isInterstate = false,
            narration = "Supply as per purchase order PO-4471"
        )
        val lines = (0 until lineCount).map { i ->
            InvoiceDocument.Line(
                description = listOf(
                    "TMT Steel Rod 12mm Fe500D grade",
                    "Binding wire 18 gauge",
                    "Freight and handling"
                )[i % 3],
                hsnCode = listOf("7214", "7217", "9965")[i % 3],
                quantity = (i + 1) * 3.0,
                unit = listOf("Nos", "Kg", "Lot")[i % 3],
                rate = 1111.11,
                taxable = 10_000.0 / lineCount,
                gstRate = 18.0,
                taxAmount = gst / lineCount,
                amount = 11_800.0 / lineCount
            )
        }
        return InvoiceAssembler.build(v, user, lines)
    }

    @Test
    fun `tax invoice preview`() {
        val branding = InvoiceBranding(terms = "Payment due within 30 days of invoice date.")
        composeRule.setContent {
            MyApplicationTheme {
                InvoicePreview(
                    InvoiceAssembler.applyBranding(doc(VoucherType.SALES, 1_800.0, 3), branding),
                    branding,
                    Modifier.width(620.dp)
                )
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/invoice_preview_sales.png")
    }

    /** A receipt carries no tax, so the HSN/Taxable/GST columns must not be drawn at all. */
    @Test
    fun `receipt preview drops tax columns`() {
        val branding = InvoiceBranding(accentArgb = InvoiceBranding.PRESET_ACCENTS[3].second)
        composeRule.setContent {
            MyApplicationTheme {
                InvoicePreview(
                    InvoiceAssembler.applyBranding(doc(VoucherType.RECEIPT, 0.0, 1), branding),
                    branding,
                    Modifier.width(620.dp)
                )
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/invoice_preview_receipt.png")
    }
}
