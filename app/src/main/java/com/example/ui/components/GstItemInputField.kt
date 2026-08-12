package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.GstCalculationResult
import com.example.service.GstCalculationService
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter

@Composable
fun GstItemInputField(
    grossAmount: Double,
    gstRate: Double,
    isInterstate: Boolean,
    onGrossAmountChange: (Double) -> Unit,
    onGstRateChange: (Double) -> Unit,
    onIsInterstateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val calculation: GstCalculationResult = remember(grossAmount, gstRate, isInterstate) {
        GstCalculationService.calculateGst(grossAmount, gstRate, isInterstate)
    }

    var amountText by remember(grossAmount) { mutableStateOf(if (grossAmount > 0) grossAmount.toString() else "") }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("gst_item_input_field")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "GST ITEM TAX CALCULATOR & SLAB SELECTOR",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RoyalPurplePrimary,
                letterSpacing = 1.sp
            )

            // Amount Input Field
            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    amountText = newValue
                    val parsed = newValue.toDoubleOrNull() ?: 0.0
                    onGrossAmountChange(parsed)
                },
                label = { Text("Gross Invoice Item Amount (₹)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gst_gross_amount_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            // GST Slab Selector
            Text(
                text = "GST Tax Slab:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.0, 5.0, 12.0, 18.0, 28.0).forEach { slab ->
                    FilterChip(
                        selected = Math.abs(gstRate - slab) < 0.1,
                        onClick = { onGstRateChange(slab) },
                        label = { Text("${slab.toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Interstate vs Intrastate Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isInterstate) "Inter-State (IGST Applicable)" else "Intra-State (CGST + SGST)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isInterstate) "Supplier & Buyer in different states" else "Supplier & Buyer in same state",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isInterstate,
                    onCheckedChange = { onIsInterstateChange(it) },
                    modifier = Modifier.testTag("interstate_toggle")
                )
            }

            // Tax Breakdown Result Surface
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = LavenderContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Taxable Base Value:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(calculation.taxableValue), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (calculation.isInterstate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("IGST (${calculation.igstRate.toInt()}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                            Text(IndianFormatter.formatRupee(calculation.igstAmount), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CGST (${calculation.cgstRate.toInt()}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                            Text(IndianFormatter.formatRupee(calculation.cgstAmount), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("SGST (${calculation.sgstRate.toInt()}%):", fontSize = 12.sp, color = RoyalPurplePrimary)
                            Text(IndianFormatter.formatRupee(calculation.sgstAmount), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Tax Amount:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            IndianFormatter.formatRupee(calculation.totalGstAmount),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccountingGreen
                        )
                    }
                }
            }
        }
    }
}
