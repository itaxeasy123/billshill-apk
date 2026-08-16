package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BalanceType
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntity
import com.example.ui.AccountingViewModel
import com.example.ui.theme.*
import com.example.utils.IndianFormatter

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
                // Header. One dismiss control: the back arrow and the trailing X both
                // called onDismiss, and the unweighted title between them was taking the
                // whole row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("ledger_mgmt_back_btn")
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
                            text = "Chart of accounts",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoyalPurplePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Create, edit and manage your ledgers",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search & Add New Row.
                //
                // "+ New Ledger" took 125dp of a 296dp row, leaving the search field about
                // 100dp of usable text width — so its own placeholder wrapped to three
                // lines and the field rendered three times its proper height. The button
                // is now an icon with a one-word label, and the placeholder is capped at
                // one line so it can never do that again at any width.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Search ledgers",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
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
                        Text("New", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
                                            // The name yields; the badge does not. Both
                                            // were unweighted, so a long ledger name took
                                            // the row and left the badge measuring against
                                            // nothing — "EXPENSE" came out as "EXPEN / SE".
                                            Text(
                                                text = ledger.name,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = RoyalPurplePrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
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
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))
                                        // Labelled "Opening", not "Bal". This row only has a
                                        // LedgerEntity, whose openingBalance is the balance the
                                        // book *started* with — it says nothing about what has
                                        // been posted since. Calling it "Bal" showed a customer
                                        // carrying Rs 4,00,000 of live invoices as "Bal: 0.0 DR",
                                        // telling the user the account was empty immediately
                                        // before offering to delete it.
                                        //
                                        // Group and opening balance are two lines, not one
                                        // wrapped run-on. Sharing the row with the edit and
                                        // delete buttons leaves about 200dp, so the single
                                        // line broke wherever it happened to run out —
                                        // usually between the amount and its DR/CR suffix,
                                        // which is the one place it must not break.
                                        Text(
                                            text = ledger.groupName,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Opening ${IndianFormatter.formatRupee(ledger.openingBalance)} ${ledger.balanceType}",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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

                                        // System accounts (CASH, BANK, STOCK, OPENING_DIFF) are
                                        // what the posting engine resolves against; the icon is
                                        // disabled rather than left armed and refused later.
                                        val deletable = ledger.systemCode == null
                                        IconButton(
                                            onClick = { deleteConfirmLedger = ledger },
                                            enabled = deletable,
                                            modifier = Modifier.testTag("delete_ledger_${ledger.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = if (deletable) "Delete Ledger"
                                                    else "System account — cannot be deleted",
                                                tint = if (deletable) AccountingRed
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
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
                        label = { Text("Ledger name *") },
                        placeholder = { Text("e.g. Car Account, Rent Expense, Ramesh Loan") },
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

                    // These chips were declared and never rendered, leaving the group as a
                    // bare free-text field. That matters beyond tidiness: which accounts
                    // the Cash and Bank tiles count is decided by the ledger's GROUP name,
                    // so a user who types "HDFC" instead of picking "Bank Accounts" has
                    // their money silently excluded from the dashboard. Offering the
                    // canonical spellings makes that the rare case rather than the default.
                    // The text field stays as an escape hatch for anything not listed.
                    Text(
                        "Tap a standard group, or type your own below",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                        commonGroups.forEach { (group, cat) ->
                            ChoiceChip(
                                label = group,
                                selected = groupName.equals(group, ignoreCase = true),
                                onClick = {
                                    groupName = group
                                    // The category is implied by the group; picking
                                    // "Bank Accounts" and leaving category on EXPENSE
                                    // would misplace the ledger on both statements.
                                    category = cat
                                },
                                fontSize = 10.sp
                            )
                        }
                    }

                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group name") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Category Chips. Five of them at intrinsic width overflow a dialog
                    // row, so they wrap rather than run off the edge.
                    Text("Category", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                        LedgerCategory.values().forEach { cat ->
                            ChoiceChip(
                                label = cat.name,
                                selected = category == cat,
                                onClick = { category = cat },
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Opening balance and its Dr/Cr sign stack instead of sharing a row:
                    // side by side, the field kept its label wrapping under a pair of
                    // chips that had already taken their intrinsic width.
                    OutlinedTextField(
                        value = openingBalanceText,
                        onValueChange = { openingBalanceText = it },
                        label = { Text("Opening balance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp) {
                        ChoiceChip(
                            label = "Debit (Dr)",
                            selected = balanceType == BalanceType.DR,
                            onClick = { balanceType = BalanceType.DR },
                            fontSize = 12.sp
                        )
                        ChoiceChip(
                            label = "Credit (Cr)",
                            selected = balanceType == BalanceType.CR,
                            onClick = { balanceType = BalanceType.CR },
                            fontSize = 12.sp
                        )
                    }

                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it.uppercase() },
                        label = { Text("Party GSTIN (optional)") },
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
    //
    // This used to read "Associated journal entries for this ledger will also be
    // removed" — an accurate description of the mechanism and a silent one about the
    // consequence, which was that the *other* legs of those same vouchers stayed behind
    // and the book came out unbalanced. Deleting a ledger with postings is now refused,
    // so the dialog reports what is blocking it instead of arming a button that fails.
    if (deleteConfirmLedger != null) {
        val target = deleteConfirmLedger!!
        val systemCode = target.systemCode

        // null while the count is in flight — the confirm button stays disabled until it
        // resolves, so the dialog never offers a delete it cannot honour.
        var usage by remember(target.id) { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(target.id) { usage = viewModel.ledgerUsage(target.id) }

        val entryCount = usage?.first ?: 0
        val voucherCount = usage?.second ?: 0
        val blocked = systemCode != null || entryCount > 0

        AlertDialog(
            onDismissRequest = { deleteConfirmLedger = null },
            title = {
                Text(
                    if (blocked) "Cannot delete '${target.name}'" else "Delete ledger '${target.name}'?",
                    fontWeight = FontWeight.Bold,
                    color = if (blocked) MaterialTheme.colorScheme.onSurface else AccountingRed
                )
            },
            text = {
                Text(
                    when {
                        systemCode != null ->
                            "'${target.name}' is a system account ($systemCode) used by every " +
                                "receipt, payment and contra. It cannot be deleted."

                        usage == null -> "Checking whether anything is posted to this ledger…"

                        entryCount > 0 ->
                            "'${target.name}' is used by $entryCount journal " +
                                (if (entryCount == 1) "entry" else "entries") +
                                " across $voucherCount " +
                                (if (voucherCount == 1) "voucher" else "vouchers") +
                                ". Delete or amend those vouchers first — deleting a voucher " +
                                "removes all of its entries together and keeps the books balanced."

                        target.openingBalance != 0.0 ->
                            "'${target.name}' has no transactions, but it carries an opening " +
                                "balance of ${IndianFormatter.formatRupee(target.openingBalance)} " +
                                "${target.balanceType}, which will be removed from the books."

                        else -> "'${target.name}' has no transactions and can be safely deleted."
                    }
                )
            },
            confirmButton = {
                if (!blocked) {
                    Button(
                        onClick = {
                            viewModel.deleteLedger(target.id, target.name)
                            deleteConfirmLedger = null
                        },
                        enabled = usage != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AccountingRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmLedger = null }) {
                    Text(if (blocked) "Close" else "Cancel")
                }
            }
        )
    }
}
