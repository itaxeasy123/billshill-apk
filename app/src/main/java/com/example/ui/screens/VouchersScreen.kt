package com.example.ui.screens

import com.example.utils.Money
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.InventoryItemEntity
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.components.ManualVoucherDialog
import com.example.ui.components.MonetaryRow
import com.example.ui.components.SalesInvoiceDialog
import com.example.ui.components.QrCodeScannerDialog
import com.example.ui.components.ScannedInvoiceData
import com.example.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import kotlinx.coroutines.launch
import com.example.utils.CsvExporter
import com.example.utils.GstCalculationService
import com.example.utils.IndianFormatter
import com.example.utils.PdfInvoiceGenerator
import java.util.Calendar
import com.example.ui.components.ChoiceChip
import com.example.ui.components.ChoiceChipRow

/** Figures carried from the Dashboard GST calculator into the voucher wizard. */
data class VoucherGstPrefill(
    val amountText: String,
    val gstRateText: String,
    val isGstInclusive: Boolean,
    val isInterstate: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VouchersScreen(
    viewModel: AccountingViewModel,
    user: UserEntity,
    initialVoucherType: VoucherType = VoucherType.Sale,
    /**
     * Figures handed over by the Dashboard's GST calculator.
     *
     * Its "Use in Voucher" button used to bind all four computed values and drop them on
     * the floor, opening a form with no GST field that hardcoded the rate to 0 — so the
     * tax the user had just calculated could not be recorded even by retyping it. They
     * arrive here instead, in the one form that actually posts Output/Input CGST-SGST-IGST
     * legs, and the user supplies the two things a calculator cannot know: the party and
     * whether it is a sale or a purchase.
     */
    gstPrefill: VoucherGstPrefill? = null
) {
    val visibleVoucherTypes = remember { // STOCK_OPENING is posted automatically when an item is created with a
    // quantity on hand. Hand-writing one has no item attached, so it would credit
    // Suspense and add no stock — putting the Balance Sheet out by its own amount.
    VoucherType.values().filter { it != VoucherType.JOURNAL && it != VoucherType.STOCK_OPENING } }
    val initialType = if (initialVoucherType == VoucherType.JOURNAL) VoucherType.SALES else initialVoucherType
    var selectedVoucherType by remember(initialType) { mutableStateOf(initialType) }
    var partyName by remember { mutableStateOf("") }
    // The buyer's GSTIN. Nothing captured this before, so GSTR-1 could never identify a
    // B2B supply and every invoice fell into the consumer tables.
    var partyGstin by remember { mutableStateOf("") }
    // How the money moved. Was inferred from a substring of the party's name (H3).
    var paymentMode by remember { mutableStateOf("CASH") }
    var amountText by remember(gstPrefill) { mutableStateOf(gstPrefill?.amountText ?: "") }
    var selectedGstRate by remember(gstPrefill) { mutableStateOf(gstPrefill?.gstRateText ?: "18") }
    var isInterstate by remember(gstPrefill) { mutableStateOf(gstPrefill?.isInterstate ?: false) }
    var narration by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("1") }
    var tagsText by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Global Search & Filter states
    var searchQuery by remember { mutableStateOf("") }
    var filterTypePill by remember { mutableStateOf("ALL") } // "ALL", "SALES", "PURCHASE", "PAYMENT", "RECEIPT"
    var dateFilterPill by remember { mutableStateOf("ALL") } // "ALL", "TODAY", "THIS_MONTH"

    var editingVoucher by remember { mutableStateOf<VoucherEntity?>(null) }
    var invoiceVoucher by remember { mutableStateOf<VoucherEntity?>(null) }
    var qrVoucher by remember { mutableStateOf<VoucherEntity?>(null) }
    var voucherToDeleteConfirm by remember { mutableStateOf<VoucherEntity?>(null) }
    var editPartyName by remember { mutableStateOf("") }
    var editAmountText by remember { mutableStateOf("") }
    var editGstRate by remember { mutableStateOf("18") }
    var editIsInterstate by remember { mutableStateOf(false) }
    var editNarration by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var editDateMillis by remember { mutableStateOf(0L) }
    var showManualVoucherDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(1) }

    var showContactsDialog by remember { mutableStateOf(false) }
    var showFavoritesDialog by remember { mutableStateOf(false) }
    // Starts empty on purpose. This list used to ship pre-populated with five invented
    // customers (Anand Traders, Sharma Electronics, ...) presented as the user's own
    // saved favourites. The companion `sampleContacts` list was worse: six invented
    // people WITH phone numbers, shown under "Select Customer from Contacts" as though
    // they had been read off the device. The app holds no READ_CONTACTS permission and
    // has no ContentResolver query, so nothing there was ever real.
    var favoriteCustomers by remember { mutableStateOf(emptyList<String>()) }

    val inventoryItems by viewModel.inventoryState.collectAsState()
    var selectedItem by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var isItemDropdownExpanded by remember { mutableStateOf(false) }

    val allLedgers by viewModel.ledgersState.collectAsState()
    var isPartyDropdownExpanded by remember { mutableStateOf(false) }

    val partySuggestions = remember(partyName, selectedVoucherType, allLedgers) {
        allLedgers.filter { ledger ->
            val matchesName = partyName.isBlank() || ledger.name.contains(partyName, ignoreCase = true)
            val matchesType = when (selectedVoucherType) {
                VoucherType.SALES, VoucherType.RECEIPT -> ledger.name.contains("Customer", ignoreCase = true) || ledger.name.contains("Debtor", ignoreCase = true) || ledger.name.contains("Cash", ignoreCase = true) || ledger.name.contains("Bank", ignoreCase = true) || ledger.name.contains("Traders", ignoreCase = true) || ledger.name.contains("Store", ignoreCase = true)
                VoucherType.PURCHASE, VoucherType.PAYMENT -> ledger.name.contains("Vendor", ignoreCase = true) || ledger.name.contains("Supplier", ignoreCase = true) || ledger.name.contains("Creditor", ignoreCase = true) || ledger.name.contains("Cash", ignoreCase = true) || ledger.name.contains("Bank", ignoreCase = true) || ledger.name.contains("Wholesale", ignoreCase = true)
                else -> true
            }
            matchesName && matchesType
        }.take(6)
    }

    val vouchers by viewModel.vouchersState.collectAsState()

    val filteredVouchers = vouchers.filter { v ->
        val matchesQuery = v.partyName.contains(searchQuery, ignoreCase = true) ||
                v.voucherNo.contains(searchQuery, ignoreCase = true) ||
                v.totalAmount.toString().contains(searchQuery) ||
                v.narration.contains(searchQuery, ignoreCase = true) ||
                v.tags.contains(searchQuery, ignoreCase = true)

        val matchesType = when (filterTypePill) {
            "ALL" -> true
            "SALES", "Sale" -> v.voucherType == VoucherType.SALES
            "PURCHASE", "Purchase" -> v.voucherType == VoucherType.PURCHASE
            "RECEIPT", "Receipt" -> v.voucherType == VoucherType.RECEIPT
            "PAYMENT", "Payment" -> v.voucherType == VoucherType.PAYMENT
            else -> v.voucherType.name.equals(filterTypePill, ignoreCase = true) || v.voucherType.displayName.equals(filterTypePill, ignoreCase = true)
        }

        val matchesDate = when (dateFilterPill) {
            "TODAY" -> {
                val cal1 = Calendar.getInstance().apply { timeInMillis = v.date }
                val cal2 = Calendar.getInstance()
                cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
            }
            "THIS_MONTH" -> {
                val cal1 = Calendar.getInstance().apply { timeInMillis = v.date }
                val cal2 = Calendar.getInstance()
                cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)
            }
            else -> true
        }

        matchesQuery && matchesType && matchesDate
    }

    var isGstInclusive by remember(gstPrefill) { mutableStateOf(gstPrefill?.isGstInclusive ?: false) }
    var showGstCalculatorTool by remember { mutableStateOf(false) }

    // Tax calculation preview (Inclusive vs Exclusive).
    // Derived from the same helpers the save handler uses, so the figures shown here
    // are by construction the figures that get posted. Previously this block computed
    // the split with its own formula while `addVoucher` ignored `isGstInclusive`
    // entirely — the preview was right and the stored voucher was wrong.
    val amount = amountText.toDoubleOrNull() ?: 0.0
    val gstRate = selectedGstRate.toDoubleOrNull() ?: 18.0
    val finalTotalAmount = GstCalculationService.toGrossAmount(amount, gstRate, isGstInclusive)
    val previewBreakdown = GstCalculationService.calculateGstBreakdown(finalTotalAmount, gstRate, isInterstate)
    val taxableValue = previewBreakdown.taxableValue
    val gstAmount = previewBreakdown.totalGstAmount
    val cgst = previewBreakdown.cgstAmount
    val sgst = previewBreakdown.sgstAmount
    val igst = previewBreakdown.igstAmount

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .testTag("vouchers_screen_scroll"),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
        item {
            // Title above, actions below.
            //
            // "Quick Entry" and "Manual" need about 200dp of a 320dp row, so no subtitle
            // could fit beside them at any weight -- it wrapped to three lines and pushed
            // the whole header down. Stacking gives the subtitle the full width and the
            // buttons an equal half each, which fits at 360dp with room to spare.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Create Voucher",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Auto-calculates statutory ledgers & balanced postings",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showQrScannerDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).height(38.dp).testTag("open_qr_scanner_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick Entry", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    FilledTonalButton(
                        onClick = { showManualVoucherDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = LavenderContainer),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).height(38.dp).testTag("open_manual_voucher_dialog_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Manual", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Entry Form Container (Multi-step Wizard)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {

                    // Step Progress Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            1 to "1. Party & Type",
                            2 to "2. Financials",
                            3 to "3. Review & Post"
                        ).forEach { (stepNum, stepLabel) ->
                            val isActive = currentStep == stepNum
                            val isCompleted = currentStep > stepNum
                            Surface(
                                onClick = { currentStep = stepNum },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isActive -> RoyalPurplePrimary
                                    isCompleted -> AccountingGreen.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                            ) {
                                Text(
                                    text = stepLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isActive -> OnAccent
                                        isCompleted -> AccountingGreen
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    when (currentStep) {
                        1 -> {
                            // STEP 1: VOUCHER TYPE & PARTY SELECTION
                            Text(
                                text = "SELECT VOUCHER TYPE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // Wrapping, not a ScrollableTabRow. Chips inside a scrollable
                            // tab strip were sliced through mid-shape at both edges --
                            // half a rounded outline hanging off each side reads as a
                            // rendering fault, and the types past the edge were invisible.
                            // The full set now fits on two lines.
                            ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                                visibleVoucherTypes.forEach { type ->
                                    ChoiceChip(
                                        label = type.displayName,
                                        selected = selectedVoucherType == type,
                                        onClick = { selectedVoucherType = type },
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Party / Customer Name Field
                            ExposedDropdownMenuBox(
                                expanded = isPartyDropdownExpanded && partySuggestions.isNotEmpty(),
                                onExpandedChange = { isPartyDropdownExpanded = !isPartyDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = partyName,
                                    onValueChange = {
                                        partyName = it
                                        isPartyDropdownExpanded = true
                                    },
                                    label = {
                                        Text(
                                            when (selectedVoucherType) {
                                                VoucherType.SALES -> "Customer Name"
                                                VoucherType.PURCHASE -> "Party Name"
                                                VoucherType.RECEIPT -> "Payer / Customer Name"
                                                VoucherType.PAYMENT -> "Payee / Vendor Name"
                                                else -> "Party Name"
                                            }
                                        )
                                    },
                                    supportingText = {
                                        Text(
                                            text = when (selectedVoucherType) {
                                                VoucherType.SALES -> "Customer ledger under Sundry Debtors"
                                                VoucherType.PURCHASE -> "Vendor ledger under Sundry Creditors"
                                                VoucherType.RECEIPT -> "Payer ledger entry"
                                                VoucherType.PAYMENT -> "Payee vendor ledger entry"
                                                else -> "Auto-creates ledger in background"
                                            },
                                            fontSize = 11.sp
                                        )
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                        .testTag("voucher_party_name_input")
                                )
                                ExposedDropdownMenu(
                                    expanded = isPartyDropdownExpanded && partySuggestions.isNotEmpty(),
                                    onDismissRequest = { isPartyDropdownExpanded = false }
                                ) {
                                    partySuggestions.forEach { l ->
                                        DropdownMenuItem(
                                            text = { Text("${l.name} (${l.balanceType.name})") },
                                            onClick = {
                                                partyName = l.name
                                                isPartyDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            if (selectedVoucherType == VoucherType.SALES) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(
                                        onClick = { showContactsDialog = true },
                                        label = { Text("Contacts", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                        modifier = Modifier.height(32.dp)
                                    )
                                    AssistChip(
                                        onClick = { showFavoritesDialog = true },
                                        label = { Text("Favorites", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = RoyalPurplePrimary) },
                                        modifier = Modifier.height(32.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { currentStep = 2 },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("step1_next_btn")
                            ) {
                                Text("Next: Financials", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }

                        2 -> {
                            // STEP 2: FINANCIALS & GST TAX
                            if (user.enableInventory && (selectedVoucherType == VoucherType.SALES || selectedVoucherType == VoucherType.PURCHASE)) {
                                ExposedDropdownMenuBox(
                                    expanded = isItemDropdownExpanded,
                                    onExpandedChange = { isItemDropdownExpanded = !isItemDropdownExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedItem?.name ?: "Select Stock Item (Optional)",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Inventory Stock Item") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isItemDropdownExpanded) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = isItemDropdownExpanded,
                                        onDismissRequest = { isItemDropdownExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("None (Accounts Only)") },
                                            onClick = {
                                                selectedItem = null
                                                isItemDropdownExpanded = false
                                            }
                                        )
                                        inventoryItems.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text("${item.name} (${item.unit}) • Stock: ${IndianFormatter.formatQuantity(item.stockQty)}") },
                                                onClick = {
                                                    selectedItem = item
                                                    // toInt() turned a 0.25% item (gold,
                                                    // rough diamonds) into 0% — a taxed
                                                    // supply filed as nil-rated.
                                                    selectedGstRate = item.gstRate.let {
                                                        if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
                                                    }
                                                    if (selectedVoucherType == VoucherType.SALES) {
                                                        amountText = (item.sellingPrice * (qtyText.toDoubleOrNull() ?: 1.0)).toString()
                                                    }
                                                    isItemDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (selectedItem != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = qtyText,
                                        onValueChange = {
                                            qtyText = it
                                            val q = it.toDoubleOrNull() ?: 1.0
                                            if (selectedItem != null && selectedVoucherType == VoucherType.SALES) {
                                                amountText = (selectedItem!!.sellingPrice * q).toString()
                                            }
                                        },
                                        label = { Text("Quantity (${selectedItem?.unit ?: "Pcs"})") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Total Amount Field
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { amountText = it },
                                label = {
                                    Text(
                                        when (selectedVoucherType) {
                                            VoucherType.SALES -> "Total Sale Amount (₹)"
                                            VoucherType.PURCHASE -> "Total Purchase Amount (₹)"
                                            VoucherType.RECEIPT -> "Amount Received (₹)"
                                            VoucherType.PAYMENT -> "Amount Paid (₹)"
                                            else -> "Total Amount (₹)"
                                        }
                                    )
                                },
                                prefix = { Text("₹ ") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voucher_amount_input")
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // How the money moved. This decided cash-vs-bank by testing
                            // whether the PARTY'S NAME contained "cash", so a receipt from
                            // "Prakash Traders" went to the cash drawer and a genuine cash
                            // sale from a party without the word went to the bank.
                            if (selectedVoucherType == VoucherType.RECEIPT ||
                                selectedVoucherType == VoucherType.PAYMENT ||
                                selectedVoucherType == VoucherType.CONTRA
                            ) {
                                Text(
                                    if (selectedVoucherType == VoucherType.CONTRA) "Money ends up in:" else "Settled by:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("CASH" to "Cash", "BANK" to "Bank").forEach { (mode, label) ->
                                        FilterChip(
                                            selected = paymentMode == mode,
                                            onClick = { paymentMode = mode },
                                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = RoyalPurplePrimary,
                                                selectedLabelColor = OnAccent
                                            ),
                                            modifier = Modifier.testTag("payment_mode_$mode")
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Returns carry GST as well: a credit note reverses the tax it
                            // originally charged, so the rate must be settable on them too.
                            if (selectedVoucherType == VoucherType.SALES ||
                                selectedVoucherType == VoucherType.PURCHASE ||
                                selectedVoucherType == VoucherType.SALES_RETURN ||
                                selectedVoucherType == VoucherType.PURCHASE_RETURN
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "GST Tax Rate & Mode",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(onClick = { showGstCalculatorTool = true }) {
                                        Icon(Icons.Default.Calculate, contentDescription = "GST Calculator", tint = RoyalPurplePrimary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                ChoiceChipRow(modifier = Modifier.fillMaxWidth()) {
                                    ChoiceChip(
                                        label = "Add GST",
                                        selected = !isGstInclusive,
                                        onClick = { isGstInclusive = false },
                                        fontSize = 11.sp
                                    )
                                    ChoiceChip(
                                        label = "Extract GST",
                                        selected = isGstInclusive,
                                        onClick = { isGstInclusive = true },
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("0", "0.25", "3", "5", "12", "18", "28", "40").forEach { rate ->
                                        FilterChip(
                                            selected = selectedGstRate == rate,
                                            onClick = { selectedGstRate = rate },
                                            label = { Text("$rate%", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RoyalPurplePrimary, selectedLabelColor = OnAccent)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Inter-State Transaction (IGST)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(if (isInterstate) "Applies 100% IGST" else "Splits 50% CGST + 50% SGST", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Not weighted: at weight(1f) against the primary action
                                // Back got ~96dp, and a Button's own content padding eats
                                // 48dp of that, leaving ~28dp for a ~30dp word -- so it
                                // rendered as "Bac / k". It now takes the width it needs and
                                // the primary action absorbs the remainder.
                                OutlinedButton(
                                    onClick = { currentStep = 1 },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Back", fontSize = 13.sp, maxLines = 1, softWrap = false)
                                }

                                Button(
                                    onClick = { currentStep = 3 },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("step2_next_btn")
                                ) {
                                    Text("Next: Review", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        3 -> {
                            // STEP 3: REVIEW, NARRATION & SAVE POSTING
                            OutlinedTextField(
                                value = narration,
                                onValueChange = { narration = it },
                                label = { Text("Narration / Invoice Notes (Optional)") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("voucher_narration_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = partyGstin,
                                onValueChange = { partyGstin = it.uppercase() },
                                label = { Text("Buyer GSTIN (optional — required for a B2B invoice)") },
                                placeholder = { Text("15 characters, e.g. 27AAPFU0939F1ZV") },
                                supportingText = {
                                    if (partyGstin.isNotBlank() && partyGstin.trim().length != 15) {
                                        Text("A GSTIN is 15 characters", fontSize = 10.sp, color = AccountingRed)
                                    } else if (partyGstin.trim().length == 15) {
                                        Text("Reported as B2B in GSTR-1 Table 4A", fontSize = 10.sp, color = AccountingGreen)
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("voucher_party_gstin_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = tagsText,
                                onValueChange = { tagsText = it },
                                label = { Text("Tags (e.g. #Travel, #ClientProject)") },
                                placeholder = { Text("e.g. #Office, #Urgent") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("voucher_tags_input")
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Automated Tax Breakdown Card Preview
                            if (amount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = LavenderContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "AUTOMATED STATUTORY POSTING PREVIEW",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DarkPurpleVariant,
                                            letterSpacing = 0.5.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        // Was the entered amount, shown beside components that sum to the gross —
                                        // the card contradicted itself by the tax on an Exclusive entry.
                                        MonetaryRow(label = "Party / Customer", amount = finalTotalAmount)
                                        MonetaryRow(label = "Taxable Base Value", amount = taxableValue)
                                        // Returns carry GST as well: a credit note reverses the tax it
                            // originally charged, so the rate must be settable on them too.
                            if (selectedVoucherType == VoucherType.SALES ||
                                selectedVoucherType == VoucherType.PURCHASE ||
                                selectedVoucherType == VoucherType.SALES_RETURN ||
                                selectedVoucherType == VoucherType.PURCHASE_RETURN
                            ) {
                                            if (!isInterstate) {
                                                MonetaryRow(label = "CGST (${gstRate / 2}%)", amount = cgst)
                                                MonetaryRow(label = "SGST (${gstRate / 2}%)", amount = sgst)
                                            } else {
                                                MonetaryRow(label = "IGST ($gstRate%)", amount = igst)
                                            }
                                        }
                                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                                        MonetaryRow(
                                            label = "Total Balanced Voucher Value",
                                            amount = amount,
                                            amountColor = AccountingGreen
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Not weighted: at weight(1f) against the primary action
                                // Back got ~96dp, and a Button's own content padding eats
                                // 48dp of that, leaving ~28dp for a ~30dp word -- so it
                                // rendered as "Bac / k". It now takes the width it needs and
                                // the primary action absorbs the remainder.
                                OutlinedButton(
                                    onClick = { currentStep = 2 },
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Back", fontSize = 13.sp, maxLines = 1, softWrap = false)
                                }

                                Button(
                                    onClick = {
                                        // GST applies only to the types whose rate control is
                                        // actually shown. The rate defaults to "18" and was
                                        // passed unconditionally, so a Rs 7,000 Contra posted
                                        // as Rs 8,260 with a phantom Rs 1,260 tax row — and the
                                        // user was never shown a rate field to explain it.
                                        val gstApplies = selectedVoucherType == VoucherType.SALES ||
                                            selectedVoucherType == VoucherType.PURCHASE ||
                                            selectedVoucherType == VoucherType.SALES_RETURN ||
                                            selectedVoucherType == VoucherType.PURCHASE_RETURN
                                        viewModel.addVoucher(
                                            type = selectedVoucherType,
                                            partyName = partyName,
                                            amountText = amountText,
                                            gstRateText = if (gstApplies) selectedGstRate else "0",
                                            isInterstate = gstApplies && isInterstate,
                                            narration = narration,
                                            selectedItemId = selectedItem?.id,
                                            qtyText = qtyText,
                                            tags = tagsText,
                                            isGstInclusive = !gstApplies || isGstInclusive,
                                            partyGstin = partyGstin,
                                            // A credit sale settles against the debtor, not
                                            // cash or bank, so those types carry CREDIT.
                                            paymentMode = when (selectedVoucherType) {
                                                VoucherType.RECEIPT, VoucherType.PAYMENT, VoucherType.CONTRA -> paymentMode
                                                else -> "CREDIT"
                                            }
                                        )
                                        partyName = ""
                                        partyGstin = ""
                                        paymentMode = "CASH"
                                        amountText = ""
                                        narration = ""
                                        tagsText = ""
                                        selectedItem = null
                                        currentStep = 1
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("submit_voucher_button")
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = OnAccent)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save Voucher", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = OnAccent, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Daybook / Audit Log Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Daybook",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${filteredVouchers.size} of ${vouchers.size} • swipe to delete",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        val csv = CsvExporter.generateTransactionHistoryCsv(filteredVouchers)
                        CsvExporter.shareCsvFile(context, "Transaction_History_${System.currentTimeMillis()}.csv", csv)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("export_vouchers_csv_btn")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Global Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                // Capped at one line: the full sentence wrapped to two lines inside the
                // field and doubled its height before anything was typed.
                placeholder = {
                    Text(
                        "Search vouchers",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("daybook_search_bar")
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Filter Pills Row 1: Voucher Type Filter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL" to "All", "Sale" to "Sale", "Purchase" to "Purchase", "Payment" to "Payment", "Receipt" to "Receipt").forEach { (key, label) ->
                    FilterChip(
                        selected = filterTypePill == key,
                        onClick = { filterTypePill = key },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RoyalPurplePrimary,
                            selectedLabelColor = OnAccent
                        )
                    )
                }
            }

            // Filter Pills Row 2: Date Filter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL" to "All Dates", "TODAY" to "Today", "THIS_MONTH" to "This Month").forEach { (key, label) ->
                    FilterChip(
                        selected = dateFilterPill == key,
                        onClick = { dateFilterPill = key },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepPurpleSecondary,
                            selectedLabelColor = OnAccent
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (filteredVouchers.isEmpty()) {
            item {
                Text(
                    text = "No matching vouchers found.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(filteredVouchers, key = { it.id }) { voucher ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == SwipeToDismissBoxValue.EndToStart || dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                            viewModel.deleteVoucher(voucher.id) { deleted ->
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Voucher ${deleted.voucher.voucherNo} deleted",
                                        actionLabel = "UNDO",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreVoucher(deleted)
                                    }
                                }
                            }
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 4.dp)
                                .background(HardRedDelete, RoundedCornerShape(24.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Swipe to Delete", color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = OnAccent)
                            }
                        }
                    },
                    content = {
                        Card(
                            onClick = {
                                editingVoucher = voucher
                                editPartyName = voucher.partyName
                                editAmountText = voucher.totalAmount.toString()
                                // Derived from the voucher's own figures and read from the
                                // voucher itself. Was "18"-or-"0" and a hardcoded false, so
                                // editing a 5% invoice rewrote it to 18% and any IGST
                                // voucher silently became CGST+SGST.
                                editGstRate = GstCalculationService
                                    .deriveGstRate(voucher.totalAmount, voucher.gstAmount)
                                    .let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                                editIsInterstate = voucher.isInterstate
                                editTags = voucher.tags
                                editDateMillis = voucher.date
                                editNarration = voucher.narration
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("voucher_item_card_${voucher.id}")
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = when (voucher.voucherType.name) {
                                                "SALES" -> AccountingGreen.copy(alpha = 0.15f)
                                                "PURCHASE" -> AccountingRed.copy(alpha = 0.15f)
                                                else -> LavenderContainer
                                            }
                                        ) {
                                            Text(
                                                text = voucher.voucherType.name,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (voucher.voucherType.name) {
                                                    "SALES" -> AccountingGreen
                                                    "PURCHASE" -> AccountingRed
                                                    else -> RoyalPurplePrimary
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = voucher.voucherNo,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = IndianFormatter.formatRupee(voucher.totalAmount),
                                        style = MonospaceTabularTextStyle,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = voucher.partyName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (voucher.narration.isNotBlank()) {
                                    Text(
                                        text = voucher.narration,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (voucher.tags.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        voucher.tags.split(",", " ").filter { it.isNotBlank() }.forEach { rawTag ->
                                            val displayTag = if (rawTag.startsWith("#")) rawTag else "#$rawTag"
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = RoyalPurplePrimary.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = displayTag,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = RoyalPurplePrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${IndianFormatter.formatDate(voucher.date)} • Tap edit / swipe delete",
                                        fontSize = 11.sp,
                                        color = RoyalPurplePrimary,
                                        fontWeight = FontWeight.Medium
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                PdfInvoiceGenerator.generateAndSharePdf(context, voucher, user)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("PDF", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                        }

                                        if (voucher.voucherType == VoucherType.SALES) {
                                            FilledTonalButton(
                                                onClick = { qrVoucher = voucher },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp).testTag("share_qr_btn_${voucher.id}")
                                            ) {
                                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pay Link", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                            }

                                            FilledTonalButton(
                                                onClick = { invoiceVoucher = voucher },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Print Bill", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                            }
                                        }

                                        IconButton(
                                            onClick = { voucherToDeleteConfirm = voucher },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Voucher", tint = AccountingRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
    )
    }

    // Voucher Deletion Confirmation Dialog
    voucherToDeleteConfirm?.let { targetVoucher ->
        AlertDialog(
            onDismissRequest = { voucherToDeleteConfirm = null },
            title = { Text("Delete Voucher #${targetVoucher.voucherNo}", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete this ${targetVoucher.voucherType.name} voucher for ${targetVoucher.partyName} (₹${targetVoucher.totalAmount})?\n\nThis will reverse all associated double-entry journal postings and automatically recalculate ledger balances.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteVoucher(targetVoucher.id) { deleted ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Voucher ${deleted.voucher.voucherNo} deleted")
                            }
                        }
                        voucherToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingRed)
                ) {
                    Text("Delete", color = OnAccent, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { voucherToDeleteConfirm = null }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    // Camera QR Code Scanner Dialog for digital invoices
    if (showQrScannerDialog) {
        QrCodeScannerDialog(
            realLedgers = allLedgers,
            initialVoucherType = selectedVoucherType,
            onDismissRequest = { showQrScannerDialog = false },
            onInvoiceScanned = { scannedData ->
                partyName = scannedData.partyName
                amountText = scannedData.amount.toString()
                // toInt() destroyed the 0.25% slab (gold, rough diamonds) and 2.5%,
                // turning a taxed supply into a nil-rated one on the chip list at :606.
                selectedGstRate = scannedData.gstRate.let {
                    if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
                }
                isInterstate = scannedData.isInterstate
                // ScannedInvoiceData.amount is the GST-INCLUSIVE total — it is what the
                // user typed as "Invoice Amount", and what an extracted invoice's Total
                // Amount column carries. The wizard defaults to Exclusive, so without this
                // the amount is grossed up a SECOND time by toGrossAmount: an Rs 11,800
                // invoice posts as Rs 13,924. The other two call sites avoid it only by
                // accident, because addVoucher's own default is inclusive.
                isGstInclusive = true
                if (scannedData.narration.isNotBlank()) {
                    narration = scannedData.narration
                }
                selectedVoucherType = scannedData.voucherType
                showQrScannerDialog = false
                currentStep = 3 // Jump to step 3 for review & save
            }
        )
    }

    // Share UPI QR Code Modal Dialog
    qrVoucher?.let { voucher ->
        // The payee VPA is derived from the user's own registered mobile number. It used
        // to fall back to the invented VPA "pay.business@upi" with payee "Business Store",
        // which produced a genuine, scannable payment instruction addressed to an account
        // the user does not own -- the customer's money would have gone nowhere, or to a
        // stranger. With no mobile number on file there is no VPA, so the QR and the
        // share-link action are withheld rather than fabricated.
        // Amount is formatted on its own (Locale.US so the separator stays a dot). Calling
        // .format() on the whole URL crashed with UnknownFormatConversionException, because
        // the URL's literal "%20" escapes parse as format specifiers.
        // Gated on a UPI ID the user actually entered. It used to be built as
        // "<their mobile number>@upi" — "@upi" is a live NPCI handle, so that address is
        // syntactically valid and may well resolve, to whoever registered that number on
        // BHIM. The share LINK is the dangerous half: unlike the undecodable QR beside it,
        // a tapped upi:// link really does open the customer's payment app pre-filled, so
        // it could move money to an account this business does not own.
        val upiPayment: Pair<String, String>? = user.upiId.trim()
            .takeIf { it.isNotBlank() }
            ?.let { payeeVpa ->
                val amountText = String.format(java.util.Locale.US, "%.2f", voucher.totalAmount)
                val url = "upi://pay?pa=$payeeVpa&pn=${user.businessName.replace(" ", "%20")}" +
                    "&am=$amountText&cu=INR&tn=Invoice%20${voucher.voucherNo.replace(" ", "%20")}"
                payeeVpa to url
            }

        AlertDialog(
            onDismissRequest = { qrVoucher = null },
            title = {
                Text("Share Payment Link", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = RoyalPurplePrimary)
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (upiPayment == null) {
                        Text(
                            text = "Add a UPI ID in Settings to share a payment link.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "No UPI ID is saved for this business, so there is nothing to " +
                                "collect this payment into. Add one under Settings \u203a Update Profile.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val (payeeVpa, upiUrl) = upiPayment
                        // The QR image that stood here was not a QR code: its data modules
                        // were a hash-derived bit pattern with no error correction and no
                        // format information, so scanners located the symbol and then failed
                        // to decode it. The UPI ID below is typed into any payment app and
                        // works; the Share button hands over a genuine upi:// link.
                        Text("UPI ID", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(payeeVpa, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        Text(
                            "Amount: ${IndianFormatter.formatRupee(voucher.totalAmount)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Pay from any UPI app \u2014 BHIM, GPay, PhonePe, Paytm",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Customer: ${voucher.partyName}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                val payeeSuffix = if (user.businessName.isNotBlank()) " to ${user.businessName}" else ""
                                val payAmountText = String.format(java.util.Locale.US, "%.2f", voucher.totalAmount)
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "UPI Payment for Invoice #${voucher.voucherNo}")
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Pay ₹$payAmountText$payeeSuffix via UPI link: $upiUrl")
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Payment Link via"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share Payment Link", maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { qrVoucher = null }) {
                    Text("Close", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    // Print Sales Invoice Dialog
    invoiceVoucher?.let { voucher ->
        SalesInvoiceDialog(
            voucher = voucher,
            user = user,
            onDismiss = { invoiceVoucher = null }
        )
    }

    // Voucher Edit/Delete Modal Dialog
    editingVoucher?.let { voucher ->
        AlertDialog(
            onDismissRequest = { editingVoucher = null },
            title = {
                Text(
                    text = "Manage ${voucher.voucherType.name} #${voucher.voucherNo}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editPartyName,
                        onValueChange = { editPartyName = it },
                        label = { Text("Party Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAmountText,
                        onValueChange = { editAmountText = it },
                        label = { Text("Total Amount (₹)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editNarration,
                        onValueChange = { editNarration = it },
                        label = { Text("Narration") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateVoucher(
                            voucherId = voucher.id,
                            type = voucher.voucherType,
                            partyName = editPartyName,
                            amountText = editAmountText,
                            gstRateText = editGstRate,
                            isInterstate = editIsInterstate,
                            narration = editNarration,
                            // Preserved rather than reset: the amendment keeps the
                            // voucher's own date, and tags used to be wiped on every edit.
                            dateMillis = editDateMillis,
                            tags = editTags
                        )
                        editingVoucher = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Update Voucher", maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteVoucher(voucher.id)
                            editingVoucher = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccountingRed)
                    ) {
                        Text("Delete", maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { editingVoucher = null }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }

    // Contacts Pick Dialog
    if (showContactsDialog) {
        AlertDialog(
            onDismissRequest = { showContactsDialog = false },
            title = { Text("Select Customer from Contacts", fontWeight = FontWeight.Bold) },
            text = {
                // Honest empty state. This dialog used to list six invented people with
                // invented phone numbers as if they had been read from the device address
                // book. The app requests no contacts permission and performs no
                // ContentResolver lookup, so there is nothing real to show here.
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No contacts available", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "Importing customers from your phone's address book isn't supported yet. Type the party name above, or pick one of your existing ledgers from the dropdown.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactsDialog = false }) {
                    Text("Close", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    // Favorites Pick Dialog
    if (showFavoritesDialog) {
        AlertDialog(
            onDismissRequest = { showFavoritesDialog = false },
            title = { Text("Favorite Customers", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (favoriteCustomers.isEmpty()) {
                        Text("No favorite customers added yet.", fontSize = 13.sp)
                    } else {
                        favoriteCustomers.forEach { favName ->
                            Card(
                                onClick = {
                                    partyName = favName
                                    showFavoritesDialog = false
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = RoyalPurplePrimary)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(favName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Text("Select", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                }
                            }
                        }
                    }
                    if (partyName.isNotBlank() && !favoriteCustomers.contains(partyName)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                favoriteCustomers = favoriteCustomers + partyName
                                showFavoritesDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save '$partyName' as Favorite", maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFavoritesDialog = false }) {
                    Text("Close", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    // Interactive GST Calculator Tool Dialog
    if (showGstCalculatorTool) {
        VoucherFormGstCalculator(
            isInterstate = isInterstate,
            initialAmount = amountText,
            initialRate = selectedGstRate,
            initialInclusive = isGstInclusive,
            onDismiss = { showGstCalculatorTool = false },
            onApply = { calcAmount, calcRate, calcInclusive ->
                amountText = calcAmount
                selectedGstRate = calcRate
                isGstInclusive = calcInclusive
                showGstCalculatorTool = false
            }
        )
    }

    // Manual Balanced Voucher Input Modal
    if (showManualVoucherDialog) {
        ManualVoucherDialog(
            allLedgers = allLedgers,
            onDismiss = { showManualVoucherDialog = false },
            onSubmit = { type, dateStr, narration, debitLedger, creditLedger, amount, tags ->
                viewModel.addManualBalancedVoucher(type, dateStr, narration, debitLedger, creditLedger, amount, tags)
                showManualVoucherDialog = false
            }
        )
    }
}

@Composable
private fun VoucherFormGstCalculator(
    /** The form's own place of supply. Without it the preview showed CGST+SGST on every
     *  interstate sale while the voucher posted IGST. */
    isInterstate: Boolean,
    initialAmount: String,
    initialRate: String,
    initialInclusive: Boolean,
    onDismiss: () -> Unit,
    onApply: (String, String, Boolean) -> Unit
) {
    var calcAmountText by remember { mutableStateOf(initialAmount) }
    var calcGstRate by remember { mutableStateOf(initialRate) }
    var calcIsInclusive by remember { mutableStateOf(initialInclusive) }

    val amount = calcAmountText.toDoubleOrNull() ?: 0.0
    val rate = calcGstRate.toDoubleOrNull() ?: 18.0

    val taxable = if (calcIsInclusive) {
        if (rate > 0) amount / (1.0 + (rate / 100.0)) else amount
    } else {
        amount
    }
    val taxAmt = Money.paise(if (calcIsInclusive) amount - taxable else amount * (rate / 100.0))
    val totalVal = Money.paise(if (calcIsInclusive) amount else amount + taxAmt)
    // Independently-rounded halves need not sum to the tax shown above them. This shares
    // the posting engine's split so the preview matches what the voucher will post.
    val (cgstVal, sgstVal, igstVal) = GstCalculationService.splitHeads(taxAmt, isInterstate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GST Calculator Tool", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = calcAmountText,
                    onValueChange = { calcAmountText = it },
                    label = { Text("Amount (₹)") },
                    prefix = { Text("₹ ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("GST tax mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                ChoiceChipRow(modifier = Modifier.fillMaxWidth()) {
                    ChoiceChip(
                        label = "Add GST",
                        selected = !calcIsInclusive,
                        onClick = { calcIsInclusive = false }
                    )
                    ChoiceChip(
                        label = "Extract GST",
                        selected = calcIsInclusive,
                        onClick = { calcIsInclusive = true }
                    )
                }

                Text("GST Rate Slab:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("0", "0.25", "3", "5", "12", "18", "28", "40").forEach { r ->
                        FilterChip(
                            selected = calcGstRate == r,
                            onClick = { calcGstRate = r },
                            label = { Text("$r%", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RoyalPurplePrimary, selectedLabelColor = OnAccent)
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MonetaryRow(label = "Taxable Base Value", amount = taxable)
                        // Was CGST+SGST unconditionally, so an inter-state entry previewed
                        // a split the voucher would never post.
                        if (isInterstate) {
                            MonetaryRow(label = "IGST ($rate%)", amount = igstVal)
                        } else {
                            MonetaryRow(label = "CGST (${rate / 2}%)", amount = cgstVal)
                            MonetaryRow(label = "SGST (${rate / 2}%)", amount = sgstVal)
                        }
                        MonetaryRow(label = "Total Tax Amount", amount = taxAmt, amountColor = RoyalPurplePrimary)
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        MonetaryRow(label = "Total Invoice Value", amount = totalVal, amountColor = RoyalPurplePrimary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(calcAmountText, calcGstRate, calcIsInclusive) },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
            ) {
                Text("Apply to Voucher", maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", maxLines = 1, softWrap = false) }
        }
    )
}
