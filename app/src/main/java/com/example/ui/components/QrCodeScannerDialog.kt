package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.VoucherType
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.RoyalPurplePrimary

import com.example.data.model.LedgerEntity

data class ScannedInvoiceData(
    val partyName: String,
    val amount: Double,
    val gstRate: Double,
    val isInterstate: Boolean,
    val narration: String,
    val voucherType: VoucherType
)

@Composable
fun QrCodeScannerDialog(
    onDismissRequest: () -> Unit,
    onInvoiceScanned: (ScannedInvoiceData) -> Unit,
    realLedgers: List<LedgerEntity> = emptyList()
) {
    // Every field starts empty. They used to open pre-filled with an invented invoice --
    // party "Cash Account", amount 15000, rate 18%, narration "Scanned Invoice OCR Entry"
    // -- so a single tap on Save posted a ₹15,000 purchase the user never made. There is
    // no camera binding and no OCR in this dialog; nothing here was ever scanned.
    var customPartyName by remember { mutableStateOf("") }
    var customAmountText by remember { mutableStateOf("") }
    var customGstRateText by remember { mutableStateOf("") }
    var customVoucherType by remember { mutableStateOf(VoucherType.PURCHASE) }
    var isInterstate by remember { mutableStateOf(false) }

    // Only real ledgers from the database. The fallback list here previously invented four
    // party accounts ("Sharma Electronics", "Apex Wholesale", ...) under a heading that
    // called them REAL LEDGERS.
    val realParties = remember(realLedgers) { realLedgers.map { it.name } }

    val parsedAmount = customAmountText.trim().toDoubleOrNull()
    val parsedGstRate = customGstRateText.trim().toDoubleOrNull()
    val canSubmit = customPartyName.isNotBlank() &&
        parsedAmount != null && parsedAmount > 0.0 &&
        parsedGstRate != null && parsedGstRate >= 0.0

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("qr_code_scanner_dialog"),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Simulated Camera Viewfinder Canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F14))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Header Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismissRequest,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Scanner", tint = Color.White)
                            }

                            Text(
                                text = "Enter Invoice Details",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            // The flash toggle was removed along with the laser animation: both
                            // simulated a live camera this dialog does not have.
                            Spacer(modifier = Modifier.size(48.dp))
                        }

                        // Scanning Frame Center
                        Box(
                            modifier = Modifier
                                .size(280.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            // The animated green "laser" sweep that used to be drawn here was
                            // removed: it simulated an active camera scan, and no camera is
                            // bound to this dialog.

                            // Corner Markers
                            Column(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Icon(Icons.Default.CropFree, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(36.dp))
                                    Icon(Icons.Default.CropFree, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(36.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Icon(Icons.Default.CropFree, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(36.dp))
                                    Icon(Icons.Default.CropFree, contentDescription = null, tint = Color(0xFF00FF88), modifier = Modifier.size(36.dp))
                                }
                            }

                            Text(
                                text = "Camera scanning isn't available yet.\nEnter the invoice details below.",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                            )
                        }

                        // Real OCR Scan Confirmation & Party Selector Card
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // Heading previously read "SCANNED INVOICE DATA (REAL LEDGERS)"
                                // above a hardcoded list of invented parties. Nothing is scanned
                                // and nothing is pre-filled; this is a manual entry form.
                                Text(
                                    text = "INVOICE DETAILS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00FF88)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Party Selection Chips from Real DB Ledgers
                                Text("Select Party Account:", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(modifier = Modifier.height(4.dp))
                                if (realParties.isEmpty()) {
                                    Text(
                                        text = "No ledgers yet — create one first to record this invoice against a party.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray.copy(alpha = 0.7f)
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        realParties.forEach { party ->
                                            FilterChip(
                                                selected = customPartyName == party,
                                                onClick = { customPartyName = party },
                                                label = { Text(party, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFF00FF88),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customAmountText,
                                        onValueChange = { customAmountText = it },
                                        label = { Text("Invoice Amount (₹)", fontSize = 10.sp, color = Color.LightGray) },
                                        placeholder = { Text("0.00", fontSize = 11.sp, color = Color.Gray) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF88),
                                            unfocusedBorderColor = Color.Gray,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )

                                    OutlinedTextField(
                                        value = customGstRateText,
                                        onValueChange = { customGstRateText = it },
                                        label = { Text("GST Rate (%)", fontSize = 10.sp, color = Color.LightGray) },
                                        placeholder = { Text("e.g. 18", fontSize = 11.sp, color = Color.Gray) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF88),
                                            unfocusedBorderColor = Color.Gray,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Submission is blocked until the party, amount and rate are all
                                // actually entered. It used to substitute ₹1,000 and 18% for
                                // anything the user left unparseable.
                                Button(
                                    onClick = {
                                        val amt = parsedAmount
                                        val gst = parsedGstRate
                                        if (amt != null && gst != null) {
                                            onInvoiceScanned(
                                                ScannedInvoiceData(
                                                    partyName = customPartyName,
                                                    amount = amt,
                                                    gstRate = gst,
                                                    isInterstate = isInterstate,
                                                    narration = "",
                                                    voucherType = customVoucherType
                                                )
                                            )
                                            onDismissRequest()
                                        }
                                    },
                                    enabled = canSubmit,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Save Voucher", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                if (!canSubmit) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Enter a party, an amount and a GST rate to save.",
                                        fontSize = 10.sp,
                                        color = Color.LightGray.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
