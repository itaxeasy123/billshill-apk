package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dao.GstSummaryReport
import com.example.data.dao.LedgerWithBalance
import com.example.data.dao.MonthlyPnlRow
import com.example.data.db.AppDatabase
import com.example.data.model.*
import com.example.data.preference.UserSettingsDataStore
import com.example.data.repository.AccountingRepository
import com.example.data.repository.BalanceTrendPoint
import com.example.data.sync.FirestoreSyncManager
import com.example.service.BookBackupStore
import com.example.service.QuickActionsWidgetProvider
import com.example.utils.FiscalYearUtils
import com.example.utils.GstCalculationService
import com.example.utils.IndianFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AccountingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = AccountingRepository(db.accountingDao())
    private val userSettingsDataStore = UserSettingsDataStore(application)
    private val firestoreSyncManager = FirestoreSyncManager()

    val persistentInventoryMode: StateFlow<Boolean> = userSettingsDataStore.enableInventoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val persistentBusinessType: StateFlow<BusinessType> = userSettingsDataStore.businessTypeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BusinessType.TRADING)

    val themeModeState: StateFlow<ThemeMode> = userSettingsDataStore.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.LIGHT)

    val dynamicColorState: StateFlow<Boolean> = userSettingsDataStore.dynamicColorFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoCloudBackupState: StateFlow<Boolean> = userSettingsDataStore.autoCloudBackupFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val backupFrequencyState: StateFlow<String> = userSettingsDataStore.backupFrequencyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DAILY")

    val lastBackupTimeState: StateFlow<String> = userSettingsDataStore.lastBackupTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Never")

    val userState: StateFlow<UserEntity?> = repository.userFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val vouchersState: StateFlow<List<VoucherEntity>> = repository.allVouchers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ledgersState: StateFlow<List<LedgerEntity>> = repository.allLedgers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryState: StateFlow<List<InventoryItemEntity>> = repository.allInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trialBalanceState: StateFlow<List<LedgerWithBalance>> = repository.trialBalanceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Analytics: real, date-ranged report data ----

    private val _analyticsDateRange = MutableStateFlow(FiscalYearUtils.currentFyBounds())
    val analyticsDateRange: StateFlow<Pair<Long, Long>> = _analyticsDateRange.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthlyPnlState: StateFlow<List<MonthlyPnlRow>> = _analyticsDateRange
        .flatMapLatest { (from, to) -> repository.monthlyPnlFlow(from, to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashBankTrendState: StateFlow<List<BalanceTrendPoint>> = repository.cashBankTrendFlow(windowDays = 30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setAnalyticsDateRange(fromMillis: Long, toMillis: Long) {
        val next = fromMillis to toMillis
        if (_analyticsDateRange.value != next) {
            _analyticsDateRange.value = next
        }
    }

    val gstSummaryState: StateFlow<GstSummaryReport> = repository.gstSummaryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GstSummaryReport(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))

    val totalSalesState: StateFlow<Double> = repository.totalSalesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalPurchasesState: StateFlow<Double> = repository.totalPurchasesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val netCashFlowState: StateFlow<Double> = repository.netCashFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val allLedgersState: StateFlow<List<LedgerEntity>> = repository.allLedgers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashBalanceState: StateFlow<Double> = repository.cashBalanceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val bankBalanceState: StateFlow<Double> = repository.bankBalanceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val cashAndBankLedgersState: StateFlow<List<LedgerWithBalance>> = repository.cashAndBankLedgersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val salesVouchersState: StateFlow<List<VoucherEntity>> = repository.salesVouchersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val purchaseVouchersState: StateFlow<List<VoucherEntity>> = repository.purchaseVouchersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val voucherConfigsState: StateFlow<List<VoucherTypeConfigEntity>> = repository.voucherConfigsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reconciliationDiscrepanciesState: StateFlow<List<ReconciliationDiscrepancyEntity>> = repository.reconciliationDiscrepanciesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unresolvedDiscrepancyCountState: StateFlow<Int> = repository.unresolvedDiscrepancyCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val autoReconciliationEnabledState: StateFlow<Boolean> = userSettingsDataStore.autoReconciliationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastReconciliationTimeState: StateFlow<String> = userSettingsDataStore.lastReconciliationTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Never")

    private val _isReconciling = MutableStateFlow(false)
    val isReconcilingState: StateFlow<Boolean> = _isReconciling.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncingState: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusState: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private val _messageEvent = MutableSharedFlow<String>()
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    init {
        // Automatically update home screen AppWidget whenever cash/bank balances update
        viewModelScope.launch {
            combine(cashBalanceState, bankBalanceState) { cash, bank -> cash + bank }
                .collect { total ->
                    val text = "Cash & Bank: ${IndianFormatter.formatRupee(total)}"
                    QuickActionsWidgetProvider.updateAllWidgets(getApplication(), text)
                }
        }

        // Schedule periodic auto reconciliation background worker
        com.example.service.LedgerReconciliationWorker.schedulePeriodicReconciliation(getApplication(), true)

        // Trigger initial reconciliation scan on startup
        triggerAutoReconciliation()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            userSettingsDataStore.saveThemeMode(mode)
            _messageEvent.emit("Theme updated to ${mode.name}!")
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            userSettingsDataStore.saveDynamicColor(enabled)
            val msg = if (enabled) "Material 3 Dynamic Coloring enabled!" else "Material 3 Dynamic Coloring disabled!"
            _messageEvent.emit(msg)
        }
    }

    fun syncDataToFirestore() {
        val user = userState.value ?: return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatusMessage.value = "Syncing local SQLite books to Firestore..."
            val result = firestoreSyncManager.syncToCloud(
                user = user,
                vouchers = vouchersState.value,
                ledgers = ledgersState.value,
                inventory = inventoryState.value
            )
            _isSyncing.value = false
            if (result.isSuccess) {
                val msg = result.getOrNull() ?: "Cloud sync complete!"
                _syncStatusMessage.value = msg
                _messageEvent.emit(msg)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Sync failed"
                _syncStatusMessage.value = "Sync error: $err"
                _messageEvent.emit("Cloud sync error: $err")
            }
        }
    }

    fun restoreDataFromFirestore() {
        val user = userState.value ?: return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatusMessage.value = "Restoring books from Firestore..."
            val result = firestoreSyncManager.restoreFromCloud(user.phoneNumber)
            _isSyncing.value = false
            if (result.isSuccess) {
                val payload = result.getOrNull()
                if (payload != null) {
                    var restoredCount = 0
                    payload.vouchers.forEach { v ->
                        repository.createVoucher(
                            voucherType = v.voucherType,
                            partyName = v.partyName,
                            amount = v.totalAmount,
                            isInterstate = v.isInterstate,
                            narration = v.narration
                        )
                        restoredCount++
                    }
                    val msg = "Restored $restoredCount vouchers from Firestore!"
                    _syncStatusMessage.value = msg
                    _messageEvent.emit(msg)
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Restore failed"
                _syncStatusMessage.value = "Restore error: $err"
                _messageEvent.emit("Cloud restore failed: $err")
            }
        }
    }

    fun login(phoneNumber: String, otp: String) {
        viewModelScope.launch {
            if (phoneNumber.length < 10) {
                _messageEvent.emit("Please enter a valid 10-digit mobile number")
                return@launch
            }
            if (otp.length < 4) {
                _messageEvent.emit("Please enter a valid OTP")
                return@launch
            }
            repository.loginWithOtp(phoneNumber, otp)
            _messageEvent.emit("Login Successful! Welcome to Mobile Accounting.")
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _messageEvent.emit("Logged out successfully.")
        }
    }

    fun updateBusinessSettings(type: BusinessType, enableInventory: Boolean) {
        viewModelScope.launch {
            repository.updateBusinessSettings(type, enableInventory)
            userSettingsDataStore.saveInventoryMode(enableInventory, type)
            _messageEvent.emit("Settings updated & persisted to DataStore!")
        }
    }

    fun updateUserProfile(user: UserEntity) {
        viewModelScope.launch {
            repository.updateUserProfile(user)
            _messageEvent.emit("Profile details & GSTIN updated successfully!")
        }
    }

    fun updateLedgerLocation(
        ledgerId: Long,
        pincode: String,
        city: String,
        state: String,
        country: String,
        gstin: String
    ) {
        viewModelScope.launch {
            repository.updateLedgerLocationDetails(ledgerId, pincode, city, state, country, gstin)
            _messageEvent.emit("Ledger pincode & tax state updated!")
        }
    }

    fun createLedger(
        name: String,
        groupName: String,
        category: LedgerCategory,
        openingBalanceText: String,
        balanceType: BalanceType,
        gstin: String = "",
        city: String = "",
        state: String = ""
    ) {
        viewModelScope.launch {
            if (name.isBlank()) {
                _messageEvent.emit("Ledger name cannot be empty")
                return@launch
            }
            val openingBalance = openingBalanceText.toDoubleOrNull() ?: 0.0
            repository.createLedger(
                name = name.trim(),
                groupName = groupName,
                category = category,
                openingBalance = openingBalance,
                balanceType = balanceType,
                gstin = gstin,
                city = city,
                state = state
            )
            _messageEvent.emit("Ledger '$name' created successfully!")
        }
    }

    fun updateLedger(
        id: Long,
        name: String,
        groupName: String,
        category: LedgerCategory,
        openingBalanceText: String,
        balanceType: BalanceType,
        gstin: String = "",
        city: String = "",
        state: String = ""
    ) {
        viewModelScope.launch {
            if (name.isBlank()) {
                _messageEvent.emit("Ledger name cannot be empty")
                return@launch
            }
            val openingBalance = openingBalanceText.toDoubleOrNull() ?: 0.0
            repository.updateLedgerDetails(
                id = id,
                name = name.trim(),
                groupName = groupName,
                category = category,
                openingBalance = openingBalance,
                balanceType = balanceType,
                gstin = gstin,
                city = city,
                state = state
            )
            _messageEvent.emit("Ledger '$name' updated successfully!")
        }
    }

    fun deleteLedger(id: Long, name: String) {
        viewModelScope.launch {
            repository.deleteLedger(id)
            _messageEvent.emit("Ledger '$name' deleted.")
        }
    }

    fun addCustomVoucher(
        type: VoucherType,
        debitLedgerName: String,
        creditLedgerName: String,
        amountText: String,
        narration: String
    ) {
        viewModelScope.launch {
            if (debitLedgerName.isBlank() || creditLedgerName.isBlank()) {
                _messageEvent.emit("Both Debit and Credit accounts are required")
                return@launch
            }
            val amount = amountText.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                _messageEvent.emit("Please enter a valid positive amount")
                return@launch
            }
            repository.createCustomVoucher(
                voucherType = type,
                debitLedgerName = debitLedgerName.trim(),
                creditLedgerName = creditLedgerName.trim(),
                amount = amount,
                narration = narration
            )
            _messageEvent.emit("Voucher saved: Dr ${debitLedgerName.trim()} / Cr ${creditLedgerName.trim()}")
        }
    }

    fun addVoucher(
        type: VoucherType,
        partyName: String,
        amountText: String,
        gstRateText: String,
        isInterstate: Boolean,
        narration: String,
        selectedItemId: Long? = null,
        qtyText: String = "1",
        tags: String = "",
        // Defaults to true because every other entry path (QR scan, bulk import, Tally
        // XML, quick entry) supplies a bill total that already includes tax. Only the
        // voucher wizard, which shows the user an Exclusive/Inclusive toggle, passes
        // the user's actual choice.
        isGstInclusive: Boolean = true
    ) {
        viewModelScope.launch {
            if (partyName.isBlank()) {
                _messageEvent.emit("Party Name is required")
                return@launch
            }
            val amount = amountText.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                _messageEvent.emit("Please enter a valid positive amount")
                return@launch
            }
            val gstRate = gstRateText.toDoubleOrNull() ?: 18.0
            val qty = qtyText.toDoubleOrNull() ?: 1.0

            // Gross up here, once, at the boundary — the repository and everything
            // below it treat the amount they receive as tax-inclusive.
            val grossAmount = GstCalculationService.toGrossAmount(amount, gstRate, isGstInclusive)

            repository.createVoucher(
                voucherType = type,
                partyName = partyName.trim(),
                amount = grossAmount,
                gstRate = gstRate,
                isInterstate = isInterstate,
                narration = narration,
                selectedItemId = selectedItemId,
                itemQuantity = qty,
                tags = tags
            )
            _messageEvent.emit("Voucher created & debits/credits balanced automatically!")
        }
    }

    fun addManualBalancedVoucher(
        type: VoucherType,
        dateStr: String,
        narration: String,
        debitLedgerName: String,
        creditLedgerName: String,
        amount: Double,
        tags: String = ""
    ) {
        viewModelScope.launch {
            if (debitLedgerName.isBlank() || creditLedgerName.isBlank()) {
                _messageEvent.emit("Both Debit and Credit ledgers are required")
                return@launch
            }
            if (amount <= 0) {
                _messageEvent.emit("Amount must be greater than zero")
                return@launch
            }
            repository.createVoucher(
                voucherType = type,
                partyName = debitLedgerName,
                amount = amount,
                gstRate = 0.0,
                isInterstate = false,
                narration = if (narration.isBlank()) "Manual voucher: DR $debitLedgerName, CR $creditLedgerName" else "$narration [DR: $debitLedgerName | CR: $creditLedgerName]",
                tags = tags
            )
            _messageEvent.emit("Manual balanced voucher posted successfully!")
        }
    }

    fun setCloudBackupSettings(context: android.content.Context, enabled: Boolean, frequency: String) {
        viewModelScope.launch {
            userSettingsDataStore.saveCloudBackupSettings(enabled, frequency)
            // Pass the frequency through -- it was persisted and then ignored, so the
            // job always ran every 24h no matter which chip the user picked.
            com.example.service.AutoBackupWorker.schedulePeriodicBackup(context, enabled, frequency)
            _messageEvent.emit(
                if (enabled) "Automatic on-device backup scheduled (${frequency.lowercase()})."
                else "Automatic on-device backup turned off."
            )
        }
    }

    /**
     * Writes a real backup file and reports what actually happened.
     *
     * This used to stamp a timestamp into DataStore and emit "backed up to Cloud
     * successfully" without writing a single byte anywhere — the Last Backup line then
     * showed a time that corresponded to no backup at all, which is the worst possible
     * failure for this feature: it removes the user's reason to make a real one.
     *
     * It is also no longer described as a cloud backup, because it isn't one. The file
     * is local; Sync Books is the Firestore path.
     */
    fun triggerManualCloudBackup() {
        viewModelScope.launch {
            try {
                val file = BookBackupStore.writeBackup(getApplication())
                val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH)
                    .format(java.util.Date())
                userSettingsDataStore.updateLastBackupTime(nowStr)
                _messageEvent.emit(
                    "Backup saved on this device: ${file.name} (${BookBackupStore.humanReadableSize(file)})"
                )
            } catch (e: Exception) {
                // Deliberately does NOT stamp the backup time on failure.
                _messageEvent.emit("Backup FAILED: ${e.message ?: "could not write backup file"}")
            }
        }
    }

    /** Backups on this device, newest first — the retrieval path H17 flagged as missing. */
    fun loadDeviceBackups(onLoaded: (List<java.io.File>) -> Unit) {
        viewModelScope.launch {
            onLoaded(BookBackupStore.listBackups(getApplication()))
        }
    }

    /**
     * Restores the books from a backup file written on this device.
     *
     * Replaces rather than merges, inside a single transaction: either the whole set of
     * books comes back or nothing changes.
     */
    fun restoreFromDeviceBackup(file: java.io.File, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val rows = BookBackupStore.restoreFrom(getApplication(), file)
                _messageEvent.emit("Books restored from ${file.name} — $rows records.")
                onResult(true)
            } catch (e: Exception) {
                _messageEvent.emit("Restore failed, books unchanged: ${e.message ?: "unreadable backup"}")
                onResult(false)
            }
        }
    }

    fun deleteVoucher(voucherId: Long, onDeleted: ((VoucherEntity) -> Unit)? = null) {
        viewModelScope.launch {
            val voucher = vouchersState.value.firstOrNull { it.id == voucherId }
            repository.deleteVoucher(voucherId)
            if (voucher != null && onDeleted != null) {
                onDeleted(voucher)
            } else {
                _messageEvent.emit("Voucher deleted & ledgers/stock updated automatically!")
            }
        }
    }

    fun restoreVoucher(voucher: VoucherEntity) {
        viewModelScope.launch {
            repository.createVoucher(
                voucherType = voucher.voucherType,
                partyName = voucher.partyName,
                amount = voucher.totalAmount,
                gstRate = if (voucher.gstAmount > 0) 18.0 else 0.0,
                isInterstate = false,
                narration = voucher.narration
            )
            _messageEvent.emit("Voucher #${voucher.voucherNo} restored successfully!")
        }
    }

    fun updateVoucher(
        voucherId: Long,
        type: VoucherType,
        partyName: String,
        amountText: String,
        gstRateText: String,
        isInterstate: Boolean,
        narration: String
    ) {
        viewModelScope.launch {
            if (partyName.isBlank()) {
                _messageEvent.emit("Party Name is required")
                return@launch
            }
            val amount = amountText.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                _messageEvent.emit("Please enter a valid positive amount")
                return@launch
            }
            val gstRate = gstRateText.toDoubleOrNull() ?: 18.0

            repository.updateVoucher(
                voucherId = voucherId,
                voucherType = type,
                partyName = partyName.trim(),
                amount = amount,
                gstRate = gstRate,
                isInterstate = isInterstate,
                narration = narration
            )
            _messageEvent.emit("Voucher updated & ledgers rebalanced automatically!")
        }
    }

    fun addInventoryItem(name: String, unit: String, hsn: String, gstRateText: String, costText: String, priceText: String) {
        viewModelScope.launch {
            if (name.isBlank()) {
                _messageEvent.emit("Item Name is required")
                return@launch
            }
            val gstRate = gstRateText.toDoubleOrNull() ?: 18.0
            val cost = costText.toDoubleOrNull() ?: 0.0
            val price = priceText.toDoubleOrNull() ?: 0.0

            repository.createInventoryItem(name.trim(), unit, hsn, gstRate, cost, price)
            _messageEvent.emit("Stock Item '$name' created!")
        }
    }

    fun loadLedgerTransactions(ledgerId: Long, onLoaded: (List<com.example.data.dao.LedgerTxEntry>) -> Unit) {
        viewModelScope.launch {
            val list = repository.getLedgerTransactions(ledgerId)
            onLoaded(list)
        }
    }

    /**
     * Produces the full-fidelity backup JSON for the user to copy or share.
     *
     * The success message no longer fires here. It used to announce "exported
     * successfully" the moment the in-memory string was built — before the file was
     * written and before the share sheet opened — so a failure in either left the user
     * with a success message and nothing else. The caller reports the real outcome.
     */
    fun exportDataToJson(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.exportBooksToJson())
            } catch (e: Exception) {
                _messageEvent.emit("Could not build backup: ${e.message ?: "unknown error"}")
            }
        }
    }

    /**
     * Restores the books from pasted backup JSON.
     *
     * Replaces rather than appends. The previous import re-posted every voucher on top
     * of whatever was already there, so restoring onto a non-empty database doubled the
     * books — and it reported success while doing it.
     */
    fun importDataFromJson(jsonStr: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val rows = repository.restoreBooksFromJson(jsonStr)
                _messageEvent.emit("Books restored — $rows records replaced the current data.")
                onResult(true)
            } catch (e: Exception) {
                _messageEvent.emit("Restore failed, books unchanged: ${e.message ?: "unreadable backup"}")
                onResult(false)
            }
        }
    }

    fun updateVoucherConfig(type: String, prefix: String, nextNumberText: String, autoIncrement: Boolean, description: String = "") {
        viewModelScope.launch {
            val nextNumber = nextNumberText.toLongOrNull() ?: 1001L
            repository.updateVoucherConfig(type, prefix, nextNumber, autoIncrement, description)
            _messageEvent.emit("Voucher type prefix updated for $type")
        }
    }

    fun triggerAutoReconciliation() {
        viewModelScope.launch {
            _isReconciling.value = true
            val count = repository.runLedgerReconciliation()
            val timestamp = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date())
            userSettingsDataStore.updateLastReconciliationTime(timestamp, count)
            _isReconciling.value = false
            _messageEvent.emit("Auto Reconciliation complete: $count discrepancy flag(s) identified.")
        }
    }

    fun resolveDiscrepancy(id: Long, resolutionNotes: String = "Resolved by user") {
        viewModelScope.launch {
            repository.resolveReconciliationDiscrepancy(id, resolutionNotes)
            _messageEvent.emit("Discrepancy marked as resolved.")
            triggerAutoReconciliation()
        }
    }

    fun toggleAutoReconciliation(enabled: Boolean) {
        viewModelScope.launch {
            userSettingsDataStore.saveAutoReconciliationSettings(enabled)
            com.example.service.LedgerReconciliationWorker.schedulePeriodicReconciliation(getApplication(), enabled)
            _messageEvent.emit(if (enabled) "Background reconciliation service enabled" else "Background reconciliation service disabled")
        }
    }
}
