package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LedgerEntity
import com.example.data.model.VoucherType
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.AccountingRed
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualVoucherDialog(
    allLedgers: List<LedgerEntity>,
    onDismiss: () -> Unit,
    onSubmit: (
        voucherType: VoucherType,
        dateStr: String,
        narration: String,
        debitLedgerName: String,
        creditLedgerName: String,
        amount: Double,
        tags: String
    ) -> Unit
) {
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date()) }
    var voucherType by remember { mutableStateOf(VoucherType.JOURNAL) }
    var dateStr by remember { mutableStateOf(todayStr) }
    var narration by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var debitLedgerName by remember { mutableStateOf(allLedgers.firstOrNull { it.name.contains("Cash", ignoreCase = true) }?.name ?: "") }
    var creditLedgerName by remember { mutableStateOf(allLedgers.firstOrNull { it.name.contains("Sales", ignoreCase = true) || it.name.contains("Revenue", ignoreCase = true) }?.name ?: "") }
    var debitAmountText by remember { mutableStateOf("") }
    var creditAmountText by remember { mutableStateOf("") }

    var isDebitDropdownExpanded by remember { mutableStateOf(false) }
    var isCreditDropdownExpanded by remember { mutableStateOf(false) }

    val debitAmt = debitAmountText.toDoubleOrNull() ?: 0.0
    val creditAmt = creditAmountText.toDoubleOrNull() ?: 0.0

    // Validation rules:
    // 1. Both debitAmt and creditAmt > 0
    // 2. debitAmt == creditAmt (Balanced entry constraint)
    // 3. Debit ledger != Credit ledger
    // 4. Narration and Date are non-blank
    val isAmountBalanced = debitAmt > 0.0 && Math.abs(debitAmt - creditAmt) < 0.01
    val isFormValid = isAmountBalanced &&
            debitLedgerName.isNotBlank() &&
            creditLedgerName.isNotBlank() &&
            debitLedgerName != creditLedgerName &&
            narration.isNotBlank() &&
            dateStr.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "RECORD MANUAL VOUCHER",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoyalPurplePrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Balanced Posting Entry Form",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Voucher Type Selector
                Text("Voucher Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(VoucherType.JOURNAL, VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.CONTRA).forEach { type ->
                        FilterChip(
                            selected = voucherType == type,
                            onClick = { voucherType = type },
                            label = { Text(type.displayName, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Date & Narration
                OutlinedTextField(
                    value = dateStr,
                    onValueChange = { dateStr = it },
                    label = { Text("Entry Date (YYYY-MM-DD)") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("manual_voucher_date_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = narration,
                    onValueChange = { narration = it },
                    label = { Text("Business Particulars / Narration") },
                    placeholder = { Text("e.g. Office maintenance expense payment") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("manual_voucher_narration_input")
                )

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (e.g. #Travel, #ClientProject)") },
                    placeholder = { Text("e.g. #Office, #Urgent") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("manual_voucher_tags_input"),
                    singleLine = true
                )

                Divider()

                // Debit Account Selector
                ExposedDropdownMenuBox(
                    expanded = isDebitDropdownExpanded,
                    onExpandedChange = { isDebitDropdownExpanded = !isDebitDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = debitLedgerName,
                        onValueChange = { debitLedgerName = it },
                        label = { Text("Debit Account (DR)") },
                        shape = RoundedCornerShape(12.dp),
                        readOnly = false,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDebitDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth().testTag("manual_debit_ledger_input")
                    )
                    ExposedDropdownMenu(
                        expanded = isDebitDropdownExpanded,
                        onDismissRequest = { isDebitDropdownExpanded = false }
                    ) {
                        allLedgers.forEach { ledger ->
                            DropdownMenuItem(
                                text = { Text(ledger.name, fontSize = 13.sp) },
                                onClick = {
                                    debitLedgerName = ledger.name
                                    isDebitDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = debitAmountText,
                    onValueChange = {
                        debitAmountText = it
                        if (creditAmountText.isBlank()) {
                            creditAmountText = it
                        }
                    },
                    label = { Text("Debit Amount (₹)") },
                    prefix = { Text("₹ ") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("manual_debit_amount_input"),
                    singleLine = true
                )

                Divider()

                // Credit Account Selector
                ExposedDropdownMenuBox(
                    expanded = isCreditDropdownExpanded,
                    onExpandedChange = { isCreditDropdownExpanded = !isCreditDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = creditLedgerName,
                        onValueChange = { creditLedgerName = it },
                        label = { Text("Credit Account (CR)") },
                        shape = RoundedCornerShape(12.dp),
                        readOnly = false,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isCreditDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth().testTag("manual_credit_ledger_input")
                    )
                    ExposedDropdownMenu(
                        expanded = isCreditDropdownExpanded,
                        onDismissRequest = { isCreditDropdownExpanded = false }
                    ) {
                        allLedgers.forEach { ledger ->
                            DropdownMenuItem(
                                text = { Text(ledger.name, fontSize = 13.sp) },
                                onClick = {
                                    creditLedgerName = ledger.name
                                    isCreditDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = creditAmountText,
                    onValueChange = { creditAmountText = it },
                    label = { Text("Credit Amount (₹)") },
                    prefix = { Text("₹ ") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("manual_credit_amount_input"),
                    singleLine = true
                )

                // Balance Validation Diagnostic Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAmountBalanced) AccountingGreen.copy(alpha = 0.12f) else AccountingRed.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isAmountBalanced) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isAmountBalanced) AccountingGreen else AccountingRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAmountBalanced) "BALANCED: DR = CR (${IndianFormatter.formatRupee(debitAmt)})" else "UNBALANCED: DR (${IndianFormatter.formatRupee(debitAmt)}) ≠ CR (${IndianFormatter.formatRupee(creditAmt)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAmountBalanced) AccountingGreen else AccountingRed
                            )
                            if (!isAmountBalanced) {
                                Text(
                                    text = "Difference: ${IndianFormatter.formatRupee(Math.abs(debitAmt - creditAmt))}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onSubmit(voucherType, dateStr, narration, debitLedgerName, creditLedgerName, debitAmt, tagsText)
                    }
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                modifier = Modifier.testTag("submit_manual_voucher_btn")
            ) {
                Text("Post Balanced Voucher")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
