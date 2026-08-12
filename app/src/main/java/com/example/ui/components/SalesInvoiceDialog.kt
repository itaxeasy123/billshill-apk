package com.example.ui.components

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
import androidx.compose.ui.graphics.Color
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
    val taxableValue = amount - gstAmount
    val isInterstate = voucher.isInterstate
    val cgst = if (!isInterstate) gstAmount / 2.0 else 0.0
    val sgst = if (!isInterstate) gstAmount / 2.0 else 0.0
    val igst = if (isInterstate) gstAmount else 0.0

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
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                            color = Color.Black,
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
                                    color = Color.Black
                                )
                                if (user.gstin.isNotBlank()) {
                                    Text("GSTIN: ${user.gstin}", fontSize = 11.sp, color = Color.DarkGray)
                                }
                                if (user.state.isNotBlank()) {
                                    Text("State: ${user.state}", fontSize = 11.sp, color = Color.DarkGray)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Invoice No: ${voucher.voucherNo}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                                Text("Date: ${IndianFormatter.formatDate(voucher.date)}", fontSize = 11.sp, color = Color.DarkGray)
                                Text("Terms: Immediate Cash/Credit", fontSize = 10.sp, color = Color.Gray)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.LightGray)

                        // Billed To
                        Text("Billed To (Customer):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(voucher.partyName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Place of Supply: ${if (isInterstate) "Out of State (Interstate)" else "Intra-State"}", fontSize = 11.sp, color = Color.DarkGray)

                        Spacer(modifier = Modifier.height(16.dp))

                        // Itemized Bill Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF3EDF7), RoundedCornerShape(12.dp))
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Description", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
                            Text("Taxable", modifier = Modifier.weight(1.0f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black, textAlign = TextAlign.End)
                            Text("GST", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black, textAlign = TextAlign.End)
                            Text("Total", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black, textAlign = TextAlign.End)
                        }

                        Divider(color = Color.LightGray)

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
                                color = Color.Black,
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
                                color = Color.Black,
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
                                color = Color.Black,
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
                                color = Color.Black,
                                textAlign = TextAlign.End
                            )
                        }

                        Divider(color = Color.LightGray)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tax Breakup Table
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFAF9FD), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Text("Tax Breakdown:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(6.dp))
                            if (!isInterstate) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("CGST:", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(
                                        text = IndianFormatter.formatRupee(cgst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.Black
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("SGST:", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(
                                        text = IndianFormatter.formatRupee(sgst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.Black
                                    )
                                }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("IGST:", fontSize = 11.sp, color = Color.DarkGray)
                                    Text(
                                        text = IndianFormatter.formatRupee(igst),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.Black
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Grand Total Box
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFEADDFF), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grand Total (Incl. Taxes)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.Black,
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
                                color = Color(0xFF21005D),
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Amount in Words: ${IndianFormatter.convertNumberToWords(amount)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.DarkGray
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Dynamic UPI Payment QR Code.
                        // The payee VPA is derived from the user's own registered mobile number.
                        // It previously fell back to the invented VPA "pay.business@upi" with
                        // payee "Business Store" -- a real, scannable payment instruction that
                        // would have sent the customer's money to an account the user does not
                        // own. With no mobile number on file there is no VPA, so no QR is drawn.
                        if (user.phoneNumber.isNotBlank()) {
                            com.example.utils.UpiQrCodeView(
                                vpa = "${user.phoneNumber}@upi",
                                payeeName = user.businessName,
                                amount = amount,
                                invoiceNo = voucher.voucherNo,
                                size = 180.dp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        } else {
                            Text(
                                text = "Add a UPI ID in Settings to show a payment QR on this invoice.",
                                fontSize = 10.sp,
                                color = Color.Gray,
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
                            Column {
                                Text("Terms & Conditions:", fontSize = 9.sp, color = Color.Gray)
                                Text("• Goods once sold will not be taken back.", fontSize = 9.sp, color = Color.Gray)
                                // The jurisdiction line was hardcoded to "Delhi" and printed on
                                // every invoice regardless of where the business actually is --
                                // a legally meaningful claim the app had no basis for. It now
                                // follows the saved state, and is dropped when the state is unknown.
                                if (user.state.isNotBlank()) {
                                    Text("• Subject to ${user.state} Jurisdiction.", fontSize = 9.sp, color = Color.Gray)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (user.businessName.isNotBlank()) {
                                    Text("For ${user.businessName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("Authorized Signatory", fontSize = 10.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}
