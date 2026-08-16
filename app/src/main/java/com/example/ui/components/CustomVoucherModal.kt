package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

    // One or two words per chip. The full descriptions these used to carry —
    // "Money Received (Loan Back)" and the like — meant one preset per line and five
    // lines of chips before the form began. Tapping a chip fills the debit account and
    // narration below it, which is where the detail belongs and where it is now visible.
    val presetOptions = listOf(
        "Vehicle" to Triple(VoucherType.PAYMENT, "Vehicle Asset Account", "Paid for vehicle purchase"),
        "Loan given" to Triple(VoucherType.PAYMENT, "Loans & Advances Given", "Money given to individual / loan"),
        "Loan repaid" to Triple(VoucherType.RECEIPT, "Loan Repayment Received", "Money returned by individual"),
        "Rent" to Triple(VoucherType.PAYMENT, "Rent & Premises Expense", "Monthly office premises rent"),
        "Machinery" to Triple(VoucherType.PAYMENT, "Machinery & Equipment", "Asset capital expense"),
        "Drawings" to Triple(VoucherType.PAYMENT, "Proprietor Drawings", "Cash withdrawn for personal expense")
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
                // Header. One dismiss control, not two: the back arrow and a trailing X
                // both called onDismiss, and the title row between them carried no weight,
                // so it claimed the full width and pushed the X clean off the screen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("custom_voucher_back_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = RoyalPurplePrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Custom voucher",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoyalPurplePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Vehicle purchase, loans given, rent and other expenses",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        text = "Start from a common purpose",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Wrapping, not a horizontal scroller. Six presets in a scrolling row
                    // showed one and a half of them against the edge of the dialog with
                    // nothing to indicate the other four existed.
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth()) {
                        presetOptions.forEach { (title, triplet) ->
                            ChoiceChip(
                                label = title,
                                selected = purposePreset == title,
                                onClick = {
                                    applyPreset(title, triplet.first, triplet.second, triplet.third)
                                },
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Voucher type", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.JOURNAL, VoucherType.CONTRA).forEach { type ->
                            ChoiceChip(
                                label = type.displayName,
                                selected = voucherType == type,
                                onClick = { voucherType = type },
                                fontSize = 11.sp
                            )
                        }
                    }

                    // The long parenthetical used to live in the label, where it wrapped
                    // to two lines inside the field and doubled its height before a
                    // character was typed. A label names the field; the explanation goes
                    // under it, where it stays one line.
                    OutlinedTextField(
                        value = debitLedgerName,
                        onValueChange = { debitLedgerName = it },
                        label = { Text("Debit account *") },
                        supportingText = { Text("Where the money goes — an expense or asset", fontSize = 11.sp) },
                        placeholder = { Text("e.g. Car Purchase, Rent Expense") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("debit_account_input")
                    )

                    OutlinedTextField(
                        value = creditLedgerName,
                        onValueChange = { creditLedgerName = it },
                        label = { Text("Credit account *") },
                        supportingText = { Text("Where the money comes from — cash or bank", fontSize = 11.sp) },
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
                        label = { Text("Narration") },
                        placeholder = { Text("e.g. Paid for vehicle purchase") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Pinned footer. Save used to be the last child of the scrolling column,
                // which on a phone put it below the fold of a form long enough to need
                // scrolling — the dialog opened showing no way to submit it.
                Spacer(modifier = Modifier.height(12.dp))

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
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save voucher", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
