package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
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
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.ui.theme.*
import com.example.utils.AccountContraEngine
import com.example.utils.ContraSuggestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntryBottomSheet(
    historicalVouchers: List<VoucherEntity>,
    onDismissRequest: () -> Unit,
    onSubmitVoucher: (type: VoucherType, partyName: String, amountText: String, narration: String, tags: String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("quick_entry_bottom_sheet")
    ) {
        var selectedType by remember { mutableStateOf(VoucherType.SALES) }
        var partyName by remember { mutableStateOf("") }
        var amountText by remember { mutableStateOf("") }
        var narration by remember { mutableStateOf("") }
        var tagsText by remember { mutableStateOf("") }
        var isSuccessSubmitted by remember { mutableStateOf(false) }

        // ML Predictions for contra account and auto-fill
        val mlSuggestions = remember(partyName, historicalVouchers) {
            AccountContraEngine.predictContraAccount(partyName, historicalVouchers)
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = RoyalPurplePrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "QUICK CASH & PAYMENT ENTRY",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Transaction Type Selector
            Text("Transaction Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedType == VoucherType.SALES,
                    onClick = { selectedType = VoucherType.SALES },
                    label = { Text("⚡ Cash Sale (In)", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccountingGreen,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedType == VoucherType.PAYMENT,
                    onClick = { selectedType = VoucherType.PAYMENT },
                    label = { Text("💸 Cash Expense (Out)", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccountingRed,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedType == VoucherType.RECEIPT,
                    onClick = { selectedType = VoucherType.RECEIPT },
                    label = { Text("📥 Customer Receipt", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RoyalPurplePrimary,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedType == VoucherType.CONTRA,
                    onClick = { selectedType = VoucherType.CONTRA },
                    label = { Text("🏦 Bank / Cash Contra", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkPurpleVariant,
                        selectedLabelColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Party Name Input with ML Contra Account Autocomplete
            OutlinedTextField(
                value = partyName,
                onValueChange = { partyName = it },
                label = { Text("Party / Account Name") },
                placeholder = { Text("e.g. Cash, Anand Traders, Office Supplies") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("quick_entry_party_input")
            )

            // ML Contra Autocomplete Chips
            if (mlSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = RoyalPurplePrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        // Was "ML Predicted Contra Accounts:". There is no model behind these
                        // -- they come from counting the user's past vouchers and from a few
                        // substring rules -- so the label no longer claims a prediction.
                        text = "Suggested Contra Accounts:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    mlSuggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = {
                                if (partyName.isBlank()) {
                                    partyName = suggestion.accountName.substringBefore(" (")
                                }
                                selectedType = suggestion.recommendedType
                            },
                            label = {
                                Text(
                                    // The percentage is shown only when it was actually
                                    // computed from the user's own voucher history. Keyword
                                    // suggestions carry no score and now show none, instead
                                    // of a hardcoded number presented as ML confidence.
                                    text = suggestion.matchConfidencePercent
                                        ?.let { "${suggestion.accountName} ($it% of past entries)" }
                                        ?: suggestion.accountName,
                                    fontSize = 11.sp
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = LavenderContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Amount Input Field
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (₹)") },
                prefix = { Text("₹ ", fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("quick_entry_amount_input")
            )

            // Quick Preset Amount Chips
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("100", "500", "1000", "2500", "5000", "10000").forEach { preset ->
                    SuggestionChip(
                        onClick = {
                            val current = amountText.toDoubleOrNull() ?: 0.0
                            val added = preset.toDouble()
                            amountText = (current + added).toInt().toString()
                        },
                        label = { Text("+₹$preset", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = narration,
                onValueChange = { narration = it },
                label = { Text("Narration / Note (Optional)") },
                placeholder = { Text("e.g. Rapid cash payment at counter") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("quick_entry_narration_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (e.g. #Travel, #ClientProject)") },
                placeholder = { Text("e.g. #Counter, #Urgent") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("quick_entry_tags_input")
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isSuccessSubmitted) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AccountingGreen.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccountingGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Quick Entry Recorded Successfully!",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccountingGreen
                        )
                    }
                }
            } else {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            val finalParty = if (partyName.isBlank()) "Cash Account" else partyName
                            onSubmitVoucher(selectedType, finalParty, amountText, narration.ifBlank { "Quick Dashboard Entry" }, tagsText)
                            isSuccessSubmitted = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (selectedType) {
                            VoucherType.SALES -> AccountingGreen
                            VoucherType.PAYMENT -> AccountingRed
                            VoucherType.RECEIPT -> RoyalPurplePrimary
                            else -> DarkPurpleVariant
                        }
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("submit_quick_entry_btn")
                ) {
                    Text(
                        text = "⚡ Record Quick ${selectedType.name} Entry",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
