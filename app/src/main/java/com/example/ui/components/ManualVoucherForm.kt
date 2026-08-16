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

    // Strict parse, matching the ViewModel. isLenient=false is the point: by default
    // SimpleDateFormat turns "2026-02-31" into 3 March and "2026-13-01" into Jan 2027,
    // so a blank-check alone let impossible dates through and posted the wrong day.
    val isDateValid = remember(dateStr) {
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
                .apply { isLenient = false }
                .parse(dateStr.trim())
        }.getOrNull() != null
    }

    val isFormValid = isAmountBalanced &&
            debitLedgerName.isNotBlank() &&
            creditLedgerName.isNotBlank() &&
            !debitLedgerName.trim().equals(creditLedgerName.trim(), ignoreCase = true) &&
            narration.isNotBlank() &&
            isDateValid

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
                // Voucher Type Selector. Four equal weights on a 360dp phone gave each
                // chip ~78dp, so "Journal" broke as "Journ / al" and "Contra (Cash/Bank)"
                // stacked four lines deep and set the height of the whole row.
                Text("Voucher Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp) {
                    listOf(VoucherType.JOURNAL, VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.CONTRA).forEach { type ->
                        ChoiceChip(
                            label = type.displayName,
                            selected = voucherType == type,
                            onClick = { voucherType = type },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
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
                        // Pick from existing ledgers only. Free text here reached
                        // getLedgerByNameOrCreate, which creates an unrecognised debit name
                        // as an EXPENSE under "General Ledgers" and an unrecognised credit
                        // name as an ASSET under "Cash/Bank Accounts" -- so crediting a new
                        // "Loan from Director" silently created it as a cash asset with the
                        // wrong sign, and it stayed wrong on every later report.
                        readOnly = true,
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
                        // Pick from existing ledgers only. Free text here reached
                        // getLedgerByNameOrCreate, which creates an unrecognised debit name
                        // as an EXPENSE under "General Ledgers" and an unrecognised credit
                        // name as an ASSET under "Cash/Bank Accounts" -- so crediting a new
                        // "Loan from Director" silently created it as a cash asset with the
                        // wrong sign, and it stayed wrong on every later report.
                        readOnly = true,
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
                Text("Post Voucher", maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
        }
    )
}
