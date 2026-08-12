package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BalanceType
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntity
import com.example.ui.AccountingViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerManagementModal(
    viewModel: AccountingViewModel,
    onDismiss: () -> Unit
) {
    val allLedgers by viewModel.allLedgersState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    var showCreateEditDialog by remember { mutableStateOf(false) }
    var editingLedger by remember { mutableStateOf<LedgerEntity?>(null) }

    var ledgerName by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("General Ledgers") }
    var category by remember { mutableStateOf(LedgerCategory.EXPENSE) }
    var openingBalanceText by remember { mutableStateOf("0") }
    var balanceType by remember { mutableStateOf(BalanceType.DR) }
    var gstin by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }

    var deleteConfirmLedger by remember { mutableStateOf<LedgerEntity?>(null) }

    val filteredLedgers = remember(allLedgers, searchQuery) {
        if (searchQuery.isBlank()) allLedgers
        else allLedgers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.groupName.contains(searchQuery, ignoreCase = true) ||
                    it.category.name.contains(searchQuery, ignoreCase = true)
        }
    }

    fun openCreate() {
        editingLedger = null
        ledgerName = ""
        groupName = "Indirect Expenses"
        category = LedgerCategory.EXPENSE
        openingBalanceText = "0"
        balanceType = BalanceType.DR
        gstin = ""
        city = ""
        state = ""
        showCreateEditDialog = true
    }

    fun openEdit(ledger: LedgerEntity) {
        editingLedger = ledger
        ledgerName = ledger.name
        groupName = ledger.groupName
        category = ledger.category
        openingBalanceText = ledger.openingBalance.toString()
        balanceType = ledger.balanceType
        gstin = ledger.gstin
        city = ledger.city
        state = ledger.state
        showCreateEditDialog = true
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
                            modifier = Modifier.testTag("ledger_mgmt_back_btn")
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
                                text = "Chart of Accounts & Ledgers",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary
                            )
                            Text(
                                text = "Create, edit & manage accounting ledgers",
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

                // Search & Add New Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search account or group...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ledger_search_input")
                    )

                    Button(
                        onClick = { openCreate() },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("add_new_ledger_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Ledger", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Ledgers List
                if (filteredLedgers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.AccountBalance,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No ledgers found",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Tap '+ New Ledger' to create an account",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredLedgers, key = { it.id }) { ledger ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = ledger.name,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = RoyalPurplePrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = RoyalPurplePrimary.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = ledger.category.name,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = RoyalPurplePrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Group: ${ledger.groupName} • Bal: ₹${ledger.openingBalance} ${ledger.balanceType}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (ledger.gstin.isNotBlank()) {
                                            Text(
                                                text = "GSTIN: ${ledger.gstin}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = { openEdit(ledger) },
                                            modifier = Modifier.testTag("edit_ledger_${ledger.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Edit Ledger",
                                                tint = DeepPurpleSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { deleteConfirmLedger = ledger },
                                            modifier = Modifier.testTag("delete_ledger_${ledger.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Ledger",
                                                tint = AccountingRed,
                                                modifier = Modifier.size(20.dp)
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
    }

    // Create / Edit Dialog
    if (showCreateEditDialog) {
        AlertDialog(
            onDismissRequest = { showCreateEditDialog = false },
            title = {
                Text(
                    text = if (editingLedger == null) "Create New Accounting Ledger" else "Edit Ledger '${editingLedger?.name}'",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoyalPurplePrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ledgerName,
                        onValueChange = { ledgerName = it },
                        label = { Text("Ledger Name * (e.g. Car Account, Rent Expense, Ramesh Loan)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger_modal_name_input")
                    )

                    Text("Group & Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    // Standard Account Groups Selection
                    val commonGroups = listOf(
                        "Fixed Assets" to LedgerCategory.ASSET,
                        "Loans & Advances" to LedgerCategory.ASSET,
                        "Indirect Expenses" to LedgerCategory.EXPENSE,
                        "Direct Expenses" to LedgerCategory.EXPENSE,
                        "Sundry Debtors" to LedgerCategory.ASSET,
                        "Sundry Creditors" to LedgerCategory.LIABILITY,
                        "Bank Accounts" to LedgerCategory.ASSET,
                        "Cash-in-hand" to LedgerCategory.ASSET,
                        "Capital Account" to LedgerCategory.EQUITY,
                        "Secured Loans" to LedgerCategory.LIABILITY,
                        "Investments" to LedgerCategory.ASSET
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { Text("Group Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Category Chips
                    Text("Category Type:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LedgerCategory.values().forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat.name, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RoyalPurplePrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = openingBalanceText,
                            onValueChange = { openingBalanceText = it },
                            label = { Text("Opening Balance (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            FilterChip(
                                selected = balanceType == BalanceType.DR,
                                onClick = { balanceType = BalanceType.DR },
                                label = { Text("Dr", fontSize = 12.sp) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = balanceType == BalanceType.CR,
                                onClick = { balanceType = BalanceType.CR },
                                label = { Text("Cr", fontSize = 12.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it.uppercase() },
                        label = { Text("Party GSTIN (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("City") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state,
                            onValueChange = { state = it },
                            label = { Text("State") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editingLedger == null) {
                            viewModel.createLedger(
                                name = ledgerName,
                                groupName = groupName,
                                category = category,
                                openingBalanceText = openingBalanceText,
                                balanceType = balanceType,
                                gstin = gstin,
                                city = city,
                                state = state
                            )
                        } else {
                            viewModel.updateLedger(
                                id = editingLedger!!.id,
                                name = ledgerName,
                                groupName = groupName,
                                category = category,
                                openingBalanceText = openingBalanceText,
                                balanceType = balanceType,
                                gstin = gstin,
                                city = city,
                                state = state
                            )
                        }
                        showCreateEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("save_ledger_btn")
                ) {
                    Text(if (editingLedger == null) "Create Ledger" else "Save Changes", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (deleteConfirmLedger != null) {
        val target = deleteConfirmLedger!!
        AlertDialog(
            onDismissRequest = { deleteConfirmLedger = null },
            title = { Text("Delete Ledger '${target.name}'?", fontWeight = FontWeight.Bold, color = AccountingRed) },
            text = {
                Text("Are you sure you want to delete this ledger? Associated journal entries for this ledger will also be removed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLedger(target.id, target.name)
                        deleteConfirmLedger = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmLedger = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
