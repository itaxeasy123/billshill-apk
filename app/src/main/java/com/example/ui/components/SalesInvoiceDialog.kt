package com.example.ui.components

import com.example.utils.GstCalculationService
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.InkBlack
import com.example.ui.theme.InvoiceHeaderWash
import com.example.ui.theme.InvoiceTaxWash
import com.example.ui.theme.InvoiceTotalInk
import com.example.ui.theme.InvoiceTotalWash
import com.example.ui.theme.MutedText
import com.example.ui.theme.MutedTextSoft
import com.example.ui.theme.MutedTextStrong
import com.example.ui.theme.PaperSurface
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter

@Composable
fun SalesInvoiceDialog(
    voucher: VoucherEntity,
    user: UserEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val amount = voucher.totalAmount
    val gstAmount = voucher.gstAmount
    val isInterstate = voucher.isInterstate
    // Halving the total twice printed CGST + SGST that exceeded the invoice's own tax
    // total by a paisa on odd-paise bases, and disagreed with the ledger legs the same
    // voucher posted. The shared split is the one the posting engine used: CGST takes
    // the odd paisa, SGST is the residual, so the heads always add back exactly.
    val taxableValue = GstCalculationService.taxableValueOf(amount, gstAmount)
    val (cgst, sgst, igst) = GstCalculationService.splitForVoucher(gstAmount, isInterstate)

    // Identity lines are emitted only when the profile actually holds them. A blank
    // business name or GSTIN is simply left out of the shared text rather than being
    // padded with a placeholder that could be read as the real registration.
    val invoiceTextSummary = buildString {
        appendLine(if (user.businessName.isNotBlank()) "TAX INVOICE - ${user.businessName}" else "TAX INVOICE")
        appendLine("Invoice No: ${voucher.voucherNo}")
        appendLine("Date: ${IndianFormatter.formatDate(voucher.date)}")
        if (user.gstin.isNotBlank()) appendLine("GSTIN: ${user.gstin}")
        appendLine("Customer: ${voucher.partyName}")
        appendLine("----------------------------------")
        appendLine("Taxable Value: ${IndianFormatter.formatRupee(taxableValue)}")
        if (isInterstate) {
            appendLine("IGST: ${IndianFormatter.formatRupee(igst)}")
        } else {
            appendLine("CGST: ${IndianFormatter.formatRupee(cgst)}")
            appendLine("SGST: ${IndianFormatter.formatRupee(sgst)}")
        }
        appendLine("Total Amount: ${IndianFormatter.formatRupee(amount)}")
        appendLine("Amount in Words: ${IndianFormatter.convertNumberToWords(amount)}")
        appendLine("----------------------------------")
        append("Thank you for your business!")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .padding(8.dp)
                .testTag("dialog_sales_invoice"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Header Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GST Tax Invoice",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row {
                        IconButton(onClick = {
                            val sendIntent: Intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, invoiceTextSummary)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Share Sales Bill")
                            context.startActivity(shareIntent)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share Bill", tint = RoyalPurplePrimary)
                        }
                        IconButton(onClick = {
                            com.example.utils.PdfInvoiceGenerator.generateAndSharePdf(context, voucher, user)
                        }) {
                            Icon(Icons.Default.Print, contentDescription = "Print / PDF A4 Invoice", tint = AccountingGreen)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Printable Tax Invoice Document Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = PaperSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                        // Header Title
                        Text(
                            text = "TAX INVOICE",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkBlack,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Business Details vs Invoice Meta
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                // Letterhead identity. These three lines used to fall back to
                                // "My Business", GSTIN "23BNJPS3408M1ZP" and "Delhi (Code: 07)"
                                // when the profile was blank -- printing a stranger's GST
                                // registration number on the user's tax invoice. A blank profile
                                // now prints a neutral dash and omits the statutory lines entirely.
                                Text(
                                    text = user.businessName.ifBlank { "—" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = InkBlack
                                )
                                if (user.gstin.isNotBlank()) {
                                    Text("GSTIN: ${user.gstin}", fontSize = 11.sp, color = MutedTextStrong)
                                }
                                if (user.state.isNotBlank()) {
                                    Text("State: ${user.state}", fontSize = 11.sp, color = MutedTextStrong)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Invoice No: ${voucher.voucherNo}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = InkBlack)
                                Text("Date: ${IndianFormatter.formatDate(voucher.date)}", fontSize = 11.sp, color = MutedTextStrong)
                                Text("Terms: Immediate Cash/Credit", fontSize = 10.sp, color = MutedText)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = MutedTextSoft)

                        // Billed To
                        Text("Billed To (Customer):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedText)
                        Text(voucher.partyName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkBlack)
                        Text("Place of Supply: ${if (isInterstate) "Out of State (Interstate)" else "Intra-State"}", fontSize = 11.sp, color = MutedTextStrong)

                        Spacer(modifier = Modifier.height(16.dp))

                        // Itemized Bill Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(InvoiceHeaderWash, RoundedCornerShape(12.dp))
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Description", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = InkBlack)
                            Text("Taxable", modifier = Modifier.weight(1.0f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = InkBlack, textAlign = TextAlign.End)
                            Text("GST", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = InkBlack, textAlign = TextAlign.End)
                            Text("Total", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = InkBlack, textAlign = TextAlign.End)
                        }

                        Divider(color = MutedTextSoft)

                        // Item Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = voucher.narration.ifBlank { "SALES entry for ${voucher.partyName}" },
                                modifier = Modifier.weight(1.1f),
                                fontSize = 10.sp,
                                color = InkBlack,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = IndianFormatter.formatRupee(taxableValue, false),
                                modifier = Modifier.weight(1.0f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = InkBlack,
                                textAlign = TextAlign.End
                            )
                            Text(
                                text = IndianFormatter.formatRupee(gstAmount, false),
                                modifier = Modifier.weight(0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = InkBlack,
                                textAlign = TextAlign.End
                            )
                            Text(
                                text = IndianFormatter.formatRupee(amount, false),
                                modifier = Modifier.weight(1.1f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = InkBlack,
                                textAlign = TextAlign.End
                            )
                        }

                        Divider(color = MutedTextSoft)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tax Breakup Table
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(InvoiceTaxWash, RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Text("Tax Breakdown:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MutedTextStrong)
                            Spacer(modifier = Modifier.height(6.dp))
                            if (!isInterstate) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("CGST:", fontSize = 11.sp, color = MutedTextStrong)
                                    Text(
                                        text = IndianFormatter.formatRupee(cgst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = InkBlack
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("SGST:", fontSize = 11.sp, color = MutedTextStrong)
                                    Text(
                                        text = IndianFormatter.formatRupee(sgst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = InkBlack
                                    )
                                }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("IGST:", fontSize = 11.sp, color = MutedTextStrong)
                                    Text(
                                        text = IndianFormatter.formatRupee(igst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = InkBlack
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Grand Total Box
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(InvoiceTotalWash, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grand Total (Incl. Taxes)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = InkBlack,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = IndianFormatter.formatRupee(amount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = InvoiceTotalInk,
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Amount in Words: ${IndianFormatter.convertNumberToWords(amount)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MutedTextStrong
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Dynamic UPI Payment QR Code.
                        // The payee VPA is derived from the user's own registered mobile number.
                        // It previously fell back to the invented VPA "pay.business@upi" with
                        // payee "Business Store" -- a real, scannable payment instruction that
                        // would have sent the customer's money to an account the user does not
                        // own. With no mobile number on file there is no VPA, so no QR is drawn.
                        // Was a QR image built by a hand-rolled "encoder" whose data region
                        // was `((r*31 + c*17 + data.hashCode()) and 1)` — no mode indicator,
                        // no length field, no Reed-Solomon, no format bits. It drew correct
                        // finder patterns, so a scanner locked on and then failed to decode,
                        // which reads to the customer as their phone being broken. The payload
                        // could not have fitted the hardcoded 25x25 grid in any case.
                        //
                        // A typed UPI ID is a complete payment affordance — every UPI app
                        // accepts one — and unlike the image, it works. It is also gated on a
                        // UPI ID the user actually entered, rather than the "<mobile>@upi"
                        // that was assembled for them.
                        if (user.upiId.isNotBlank()) {
                            Text(
                                text = "PAY VIA UPI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "UPI ID: ${user.upiId}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Amount: ${IndianFormatter.formatRupee(amount)}",
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Pay from any UPI app \u2014 BHIM, GPay, PhonePe, Paytm",
                                fontSize = 9.sp,
                                color = MutedText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "Add your UPI ID under Settings \u203a Update Profile to " +
                                    "show payment details on this invoice.",
                                fontSize = 10.sp,
                                color = MutedText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Signature Block
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Terms & Conditions:", fontSize = 9.sp, color = MutedText)
                                Text("• Goods once sold will not be taken back.", fontSize = 9.sp, color = MutedText)
                                // The jurisdiction line was hardcoded to "Delhi" and printed on
                                // every invoice regardless of where the business actually is --
                                // a legally meaningful claim the app had no basis for. It now
                                // follows the saved state, and is dropped when the state is unknown.
                                if (user.state.isNotBlank()) {
                                    Text("• Subject to ${user.state} Jurisdiction.", fontSize = 9.sp, color = MutedText)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (user.businessName.isNotBlank()) {
                                    Text("For ${user.businessName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkBlack)
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("Authorized Signatory", fontSize = 10.sp, color = MutedTextStrong)
                            }
                        }
                    }
                }
            }
        }
    }
}
