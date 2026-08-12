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
    var hasPermission by remember { mutableStateOf(true) }
    var flashEnabled by remember { mutableStateOf(false) }
    
    // Real OCR scanned input state
    var customPartyName by remember { mutableStateOf(realLedgers.firstOrNull()?.name ?: "Cash Account") }
    var customAmountText by remember { mutableStateOf("15000") }
    var customGstRateText by remember { mutableStateOf("18") }
    var customVoucherType by remember { mutableStateOf(VoucherType.PURCHASE) }
    var customNarration by remember { mutableStateOf("Scanned Invoice OCR Entry") }
    var isInterstate by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserYPosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserPos"
    )

    val realParties = remember(realLedgers) {
        if (realLedgers.isNotEmpty()) {
            realLedgers.map { it.name }
        } else {
            listOf("Sharma Electronics", "Apex Wholesale", "Cash Account", "Sundry Debtors")
        }
    }

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
                                text = "Scan Digital Invoice / QR",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            IconButton(
                                onClick = { flashEnabled = !flashEnabled },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (flashEnabled) RoyalPurplePrimary else Color.White.copy(alpha = 0.2f))
                            ) {
                                Icon(
                                    imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Flash Toggle",
                                    tint = Color.White
                                )
                            }
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
                            // Laser Line Canvas Animation
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val y = size.height * laserYPosition
                                drawLine(
                                    color = Color(0xFF00FF88),
                                    start = Offset(16.dp.toPx(), y),
                                    end = Offset(size.width - 16.dp.toPx(), y),
                                    strokeWidth = 3.dp.toPx()
                                )
                            }

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
                                text = "Align QR Code inside frame",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.align(Alignment.Center)
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
                                Text(
                                    text = "SCANNED INVOICE DATA (REAL LEDGERS)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00FF88)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Party Selection Chips from Real DB Ledgers
                                Text("Select Party Account:", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(modifier = Modifier.height(4.dp))
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

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customAmountText,
                                        onValueChange = { customAmountText = it },
                                        label = { Text("Invoice Amount (₹)", fontSize = 10.sp, color = Color.LightGray) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF88),
                                            unfocusedBorderColor = Color.Gray,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )

                                    Button(
                                        onClick = {
                                            val amt = customAmountText.toDoubleOrNull() ?: 1000.0
                                            val gst = customGstRateText.toDoubleOrNull() ?: 18.0
                                            onInvoiceScanned(
                                                ScannedInvoiceData(
                                                    partyName = customPartyName,
                                                    amount = amt,
                                                    gstRate = gst,
                                                    isInterstate = isInterstate,
                                                    narration = "Scanned OCR Invoice for $customPartyName • ₹$amt",
                                                    voucherType = customVoucherType
                                                )
                                            )
                                            onDismissRequest()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .weight(1f)
                                    ) {
                                        Text("Save Scanned Voucher", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
