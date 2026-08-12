package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomVoucherModal(
    viewModel: AccountingViewModel,
    onDismiss: () -> Unit
) {
    // All fields start empty. This form used to open pre-filled with a complete vehicle
    // purchase -- preset "Car / Vehicle Purchase", debit "Car Account (Fixed Asset)",
    // credit "Cash-in-hand" and the narration "Purchase of vehicle for business/personal
    // use" -- so typing an amount and tapping Save posted a fabricated asset purchase
    // into the books. The preset chips below remain, but as choices the user taps rather
    // than one silently pre-selected for them.
    var voucherType by remember { mutableStateOf(VoucherType.PAYMENT) }
    var purposePreset by remember { mutableStateOf("") }
    var debitLedgerName by remember { mutableStateOf("") }
    var creditLedgerName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }

    val presetOptions = listOf(
        "Car / Vehicle Purchase" to Triple(VoucherType.PAYMENT, "Vehicle Asset Account", "Paid for vehicle purchase"),
        "Money Given (Loan/Advance)" to Triple(VoucherType.PAYMENT, "Loans & Advances Given", "Money given to individual / loan"),
        "Money Received (Loan Back)" to Triple(VoucherType.RECEIPT, "Loan Repayment Received", "Money returned by individual"),
        "Rent / Office Expense" to Triple(VoucherType.PAYMENT, "Rent & Premises Expense", "Monthly office premises rent"),
        "Machinery / Asset" to Triple(VoucherType.PAYMENT, "Machinery & Equipment", "Asset capital expense"),
        "Personal Drawings / Cash" to Triple(VoucherType.PAYMENT, "Proprietor Drawings", "Cash withdrawn for personal expense")
    )

    fun applyPreset(title: String, type: VoucherType, debitName: String, desc: String) {
        purposePreset = title
        voucherType = type
        debitLedgerName = debitName
        narration = desc
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header with Back Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("custom_voucher_back_btn")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = RoyalPurplePrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Create Custom Voucher / Expense",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary
                            )
                            Text(
                                text = "Car purchase, loans given, rent & miscellaneous expenses",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Quick Purpose Presets:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetOptions.forEach { (title, triplet) ->
                            FilterChip(
                                selected = purposePreset == title,
                                onClick = {
                                    applyPreset(title, triplet.first, triplet.second, triplet.third)
                                },
                                label = { Text(title, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RoyalPurplePrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Voucher Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.JOURNAL, VoucherType.CONTRA).forEach { type ->
                            FilterChip(
                                selected = voucherType == type,
                                onClick = { voucherType = type },
                                label = { Text(type.displayName, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = debitLedgerName,
                        onValueChange = { debitLedgerName = it },
                        label = { Text("Debit Account (Where money goes / Expense / Asset) *") },
                        placeholder = { Text("e.g. Car Purchase, Rent Expense, Loan to Friend") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("debit_account_input")
                    )

                    OutlinedTextField(
                        value = creditLedgerName,
                        onValueChange = { creditLedgerName = it },
                        label = { Text("Credit Account (Paid from Cash/Bank/Source) *") },
                        placeholder = { Text("e.g. Cash-in-hand, HDFC Bank Ltd") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("credit_account_input")
                    )

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (₹) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_voucher_amount_input")
                    )

                    OutlinedTextField(
                        value = narration,
                        onValueChange = { narration = it },
                        label = { Text("Narration / Remarks") },
                        placeholder = { Text("e.g. Paid for vehicle purchase") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Save is blocked until both ledgers and a positive amount are entered,
                    // so an empty form can no longer post a voucher.
                    val canSaveVoucher = debitLedgerName.isNotBlank() &&
                        creditLedgerName.isNotBlank() &&
                        (amountText.trim().toDoubleOrNull() ?: 0.0) > 0.0

                    Button(
                        onClick = {
                            viewModel.addCustomVoucher(
                                type = voucherType,
                                debitLedgerName = debitLedgerName,
                                creditLedgerName = creditLedgerName,
                                amountText = amountText,
                                narration = narration
                            )
                            onDismiss()
                        },
                        enabled = canSaveVoucher,
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_custom_voucher_btn")
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Voucher Entry", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
