package com.example.ui.components

import com.example.utils.Money
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.GstCalculationService
import com.example.utils.GstTaxBreakdown
import com.example.utils.IndianFormatter

@Composable
fun GstCalculatorModal(
    onDismiss: () -> Unit,
    onApplyToVoucher: ((baseAmount: Double, gstRate: Double, isExclusive: Boolean, isInterstate: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var amountText by remember { mutableStateOf("1000") }
    var selectedSlab by remember { mutableStateOf(18.0) } // Default 18%
    var customSlabText by remember { mutableStateOf("") }
    var isCustomSelected by remember { mutableStateOf(false) }
    var isExclusiveMode by remember { mutableStateOf(true) } // Exclusive vs Inclusive
    var isInterstate by remember { mutableStateOf(false) } // CGST+SGST vs IGST

    val effectiveRate = if (isCustomSelected) {
        customSlabText.toDoubleOrNull() ?: 0.0
    } else {
        selectedSlab
    }

    val inputAmount = amountText.toDoubleOrNull() ?: 0.0

    // GST Calculation logic
    val breakdown = remember(inputAmount, effectiveRate, isExclusiveMode, isInterstate) {
        if (isExclusiveMode) {
            // Exclusive: input is Taxable Base Amount. Total = Base + GST
            // The exclusive branch quantised nothing: the total itself carried binary
            // noise before it was halved, and the halves were then rounded independently
            // so they need not add back to it. Same convention as the posting engine.
            val taxable = Money.paise(inputAmount)
            val gstAmount = Money.paise((taxable * effectiveRate) / 100.0)
            val total = Money.paise(taxable + gstAmount)
            val (cgstAmt, sgstAmt, igstAmt) =
                GstCalculationService.splitHeads(gstAmount, isInterstate)
            val cgstRate = if (isInterstate) 0.0 else effectiveRate / 2.0
            val sgstRate = if (isInterstate) 0.0 else effectiveRate / 2.0
            val igstRate = if (isInterstate) effectiveRate else 0.0

            GstTaxBreakdown(
                grossAmount = total,
                taxableValue = taxable,
                totalGstAmount = gstAmount,
                gstRatePercentage = effectiveRate,
                isInterstate = isInterstate,
                cgstRate = cgstRate,
                sgstRate = sgstRate,
                igstRate = igstRate,
                cgstAmount = cgstAmt,
                sgstAmount = sgstAmt,
                igstAmount = igstAmt
            )
        } else {
            // Inclusive: input is Gross Total. Extract Taxable Base and GST
            GstCalculationService.calculateGstBreakdown(
                totalAmountInclusive = inputAmount,
                gstRatePercentage = effectiveRate,
                isInterstate = isInterstate
            )
        }
    }

    fun formatDecimal(value: Double): String = String.format("%.2f", value)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .testTag("gst_calculator_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // weight(1f) so the title yields to the close button rather than
                    // claiming the full width and pushing it off the edge.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = RoyalPurplePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Calculate,
                                    contentDescription = null,
                                    tint = RoyalPurplePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "GST CALCULATOR",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "CGST, SGST and IGST breakdown",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_gst_calc_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close GST Calculator")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Inputs and Results
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Amount Input Box
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = {
                            Text(
                                if (isExclusiveMode) "Base Taxable Amount (₹)" else "Total Gross Invoice Amount (₹)",
                                fontSize = 12.sp
                            )
                        },
                        placeholder = { Text("e.g. 10000") },
                        leadingIcon = {
                            Text("₹", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        },
                        trailingIcon = {
                            if (amountText.isNotEmpty()) {
                                IconButton(onClick = { amountText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear Amount", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gst_calc_amount_input")
                    )

                    // 2. Calculation Mode Toggle (Exclusive vs Inclusive)
                    Text("Calculation type", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth()) {
                        ChoiceChip(
                            label = "Add GST",
                            selected = isExclusiveMode,
                            onClick = { isExclusiveMode = true },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            leadingIcon = {
                                if (isExclusiveMode) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            testTag = "gst_calc_exclusive_chip"
                        )

                        ChoiceChip(
                            label = "Extract GST",
                            selected = !isExclusiveMode,
                            onClick = { isExclusiveMode = false },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            selectedContainerColor = DeepPurpleSecondary,
                            leadingIcon = {
                                if (!isExclusiveMode) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            testTag = "gst_calc_inclusive_chip"
                        )
                    }

                    // 3. Tax Rate Slabs Selection
                    //
                    // Nine chips at Modifier.weight(1f) is 33dp each on a 360dp phone —
                    // under the minimum touch target before padding — so every label
                    // measured to zero width and the user picked a tax rate from eight
                    // identical blank pills. A rate chip is as wide as "0.25%".
                    Text("GST rate", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp) {
                        // 0.0 was missing and 0.25 rendered as "0" via toInt(), so the
                        // first chip read 0% and applied a quarter percent — and exempt or
                        // nil-rated supplies had no chip at all, only "Custom". On a
                        // Rs 50,00,000 exempt supply that is Rs 12,500 of tax charged on
                        // something that carries none.
                        listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { slab ->
                            ChoiceChip(
                                label = if (slab % 1.0 == 0.0) "${slab.toInt()}%" else "$slab%",
                                selected = !isCustomSelected && selectedSlab == slab,
                                onClick = {
                                    selectedSlab = slab
                                    isCustomSelected = false
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        ChoiceChip(
                            label = "Custom",
                            selected = isCustomSelected,
                            onClick = { isCustomSelected = true },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            selectedContainerColor = AmberGold
                        )
                    }

                    if (isCustomSelected) {
                        OutlinedTextField(
                            value = customSlabText,
                            onValueChange = { customSlabText = it },
                            label = { Text("Custom GST Percentage (%)") },
                            placeholder = { Text("e.g. 3.0 or 15.0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 4. Supply Type (Intra-state vs Inter-state)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isInterstate) "Inter-State Supply (IGST)" else "Intra-State Supply (CGST + SGST)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isInterstate) "100% IGST applies" else "50% CGST + 50% SGST split",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isInterstate,
                            onCheckedChange = { isInterstate = it },
                            colors = SwitchDefaults.colors(
                            // The thumb is white ON the purple track. Setting the
                            // thumb to RoyalPurplePrimary made it the same colour as
                            // the track M3 already derives from colorScheme.primary,
                            // so the thumb vanished and the switch rendered as a
                            // solid purple box with no visible on/off position.
                            checkedThumbColor = OnAccent,
                            checkedTrackColor = RoyalPurplePrimary
                        )
                        )
                    }

                    // 5. Formatted Calculation Breakdown Card
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.6f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RoyalPurplePrimary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().testTag("gst_calc_breakdown_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CALCULATED BREAKDOWN",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurplePrimary,
                                    letterSpacing = 0.8.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = RoyalPurplePrimary
                                ) {
                                    Text(
                                        text = "${formatDecimal(effectiveRate)}% GST",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OnAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Taxable Value
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Taxable Base Amount:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    IndianFormatter.formatRupee(breakdown.taxableValue),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // GST Components
                            if (isInterstate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("IGST (${formatDecimal(breakdown.igstRate)}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                                    Text(
                                        IndianFormatter.formatRupee(breakdown.igstAmount),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("CGST (${formatDecimal(breakdown.cgstRate)}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                                    Text(
                                        IndianFormatter.formatRupee(breakdown.cgstAmount),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("SGST (${formatDecimal(breakdown.sgstRate)}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                                    Text(
                                        IndianFormatter.formatRupee(breakdown.sgstAmount),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total GST Amount:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Text(
                                    IndianFormatter.formatRupee(breakdown.totalGstAmount),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurplePrimary
                                )
                            }

                            Divider(modifier = Modifier.padding(vertical = 8.dp), color = RoyalPurplePrimary.copy(alpha = 0.2f))

                            // Total Gross Invoice
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Total Gross Amount:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    IndianFormatter.formatRupee(breakdown.grossAmount),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccountingGreen
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Pinned answer.
                //
                // The full breakdown card is the last thing in the scrolling region, so on
                // a 360x800 phone the calculator opened with its own result below the fold:
                // enter an amount, pick a rate, and then scroll to find out what it came
                // to. The two figures a user actually came for now sit above the buttons
                // and never move; the per-head split stays in the card above.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = RoyalPurplePrimary,
                    modifier = Modifier.fillMaxWidth().testTag("gst_calc_pinned_total")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "GST at ${formatDecimal(effectiveRate)}%",
                                fontSize = 10.sp,
                                color = OnAccent.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                            Text(
                                text = IndianFormatter.formatRupee(breakdown.totalGstAmount),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnAccent,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Total",
                                fontSize = 10.sp,
                                color = OnAccent.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                            Text(
                                text = IndianFormatter.formatRupee(breakdown.grossAmount),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnAccent,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val summaryText = """
                                    GST Calculation Breakdown:
                                    Mode: ${if (isExclusiveMode) "Exclusive" else "Inclusive"}
                                    Taxable Base Value: ${IndianFormatter.formatRupee(breakdown.taxableValue)}
                                    GST Rate: ${formatDecimal(effectiveRate)}%
                                    ${if (isInterstate) "IGST: ${IndianFormatter.formatRupee(breakdown.igstAmount)}" else "CGST: ${IndianFormatter.formatRupee(breakdown.cgstAmount)}\nSGST: ${IndianFormatter.formatRupee(breakdown.sgstAmount)}"}
                                    Total GST Tax: ${IndianFormatter.formatRupee(breakdown.totalGstAmount)}
                                    Total Invoice Amount: ${IndianFormatter.formatRupee(breakdown.grossAmount)}
                                """.trimIndent()

                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("GST_Calculation", summaryText))
                                Toast.makeText(context, "Calculation copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("copy_gst_summary_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Summary", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (onApplyToVoucher != null) {
                            Button(
                                onClick = {
                                    onApplyToVoucher(
                                        if (isExclusiveMode) breakdown.taxableValue else breakdown.grossAmount,
                                        effectiveRate,
                                        isExclusiveMode,
                                        isInterstate
                                    )
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                modifier = Modifier.weight(1f).testTag("create_voucher_from_gst_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Use in Voucher", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close Calculator", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
