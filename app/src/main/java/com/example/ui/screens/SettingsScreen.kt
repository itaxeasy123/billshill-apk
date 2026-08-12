package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.data.model.BusinessType
import com.example.data.model.ThemeMode
import com.example.data.model.UserEntity
import com.example.ui.AccountingViewModel
import com.example.ui.theme.*
import com.example.utils.PostalPincodeService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: AccountingViewModel,
    user: UserEntity
) {
    var showAddItemDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showGstCertificateModal by remember { mutableStateOf(false) }
    var showExportJsonDialog by remember { mutableStateOf(false) }
    var showImportJsonDialog by remember { mutableStateOf(false) }
    var showRestoreBackupDialog by remember { mutableStateOf(false) }
    var deviceBackups by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var backupPendingConfirm by remember { mutableStateOf<java.io.File?>(null) }
    var exportShareFailed by remember { mutableStateOf(false) }
    var showVoucherTypeManagementModal by remember { mutableStateOf(false) }
    var showReconciliationModal by remember { mutableStateOf(false) }
    var exportedJsonText by remember { mutableStateOf("") }
    var importJsonText by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val currentThemeMode by viewModel.themeModeState.collectAsState()
    val dynamicColor by viewModel.dynamicColorState.collectAsState()
    val isSyncing by viewModel.isSyncingState.collectAsState()
    val syncStatusMsg by viewModel.syncStatusState.collectAsState()

    val autoCloudBackup by viewModel.autoCloudBackupState.collectAsState()
    val backupFrequency by viewModel.backupFrequencyState.collectAsState()
    val lastBackupTime by viewModel.lastBackupTimeState.collectAsState()

    val reconciliationDiscrepancies by viewModel.reconciliationDiscrepanciesState.collectAsState()
    val unresolvedDiscrepancyCount by viewModel.unresolvedDiscrepancyCountState.collectAsState()
    val isReconciling by viewModel.isReconcilingState.collectAsState()
    val autoReconciliationEnabled by viewModel.autoReconciliationEnabledState.collectAsState()
    val lastReconciliationTime by viewModel.lastReconciliationTimeState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("settings_screen_scroll"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                text = "Account Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Business Profile & GSTIN Certificate Details Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BUSINESS PROFILE & TAX DETAILS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoyalPurplePrimary,
                            letterSpacing = 1.sp
                        )
                        IconButton(onClick = { showEditProfileDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Profile", tint = RoyalPurplePrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = user.businessName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Proprietor / Owner: ${user.firstName} ${user.middleName} ${user.surname}".replace("\\s+".toRegex(), " ").trim(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Father Name: ${user.fatherName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "DOB: ${user.dob}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (user.dod.isNotBlank()) {
                            Text(
                                text = "DOD: ${user.dod}",
                                fontSize = 12.sp,
                                color = AccountingRed
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = user.phoneNumber, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "${user.address}, ${user.city}, ${user.state} - ${user.pincode}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // GSTIN Certificate Quick View
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "GSTIN TAX REGISTRATION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = user.gstin, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccountingGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = user.gstStatus,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccountingGreen,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showGstCertificateModal = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "GST Certificate",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = { showEditProfileDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Update Profile",
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Business Category Toggle
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "INVENTORY & VOUCHER CONFIGURATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Voucher Types & Prefix Numbering Button
                    OutlinedButton(
                        onClick = { showVoucherTypeManagementModal = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("voucher_numbering_config_btn")
                    ) {
                        Icon(Icons.Default.Numbers, contentDescription = null, modifier = Modifier.size(18.dp), tint = RoyalPurplePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configure Voucher Numbering & Prefixes", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (user.enableInventory) "Inventory" else "No Inventory",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (user.enableInventory) "Stock tracking and items enabled" else "Accounts only mode active",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = user.enableInventory,
                            onCheckedChange = { isEnabled ->
                                val newType = if (isEnabled) BusinessType.TRADING else BusinessType.SERVICE
                                viewModel.updateBusinessSettings(newType, isEnabled)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = RoyalPurplePrimary)
                        )
                    }

                    if (user.enableInventory) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showAddItemDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create New Stock Item", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Theme & Appearance Settings Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("theme_selection_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "APPEARANCE & THEME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose preferred color scheme for accounting clarity and eye comfort.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.values().forEach { mode ->
                            val isSelected = currentThemeMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = {
                                    Text(
                                        text = when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (mode) {
                                            ThemeMode.SYSTEM -> Icons.Default.SettingsSystemDaydream
                                            ThemeMode.LIGHT -> Icons.Default.LightMode
                                            ThemeMode.DARK -> Icons.Default.DarkMode
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RoyalPurplePrimary,
                                    selectedLabelColor = Color.White,
                                    selectedLeadingIconColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Material 3 Dynamic Color",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Adapt theme palette dynamically to wallpaper colors (Android 12+)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = RoyalPurplePrimary),
                            modifier = Modifier.testTag("dynamic_color_switch")
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Data Export & Digital Signature Pad Card (Moved from Dashboard)
        item {
            var showExportLedgerDialog by remember { mutableStateOf(false) }
            var showSignaturePadDialog by remember { mutableStateOf(false) }
            var selectedLedgerForExport by remember { mutableStateOf("ALL LEDGERS") }

            val trialBalance by viewModel.trialBalanceState.collectAsState()
            val allVouchers by viewModel.vouchersState.collectAsState()

            if (showSignaturePadDialog) {
                com.example.ui.components.SignaturePadDialog(
                    onDismissRequest = { showSignaturePadDialog = false },
                    onSignatureSaved = { _, _ ->
                        showSignaturePadDialog = false
                    }
                )
            }

            if (showExportLedgerDialog) {
                AlertDialog(
                    onDismissRequest = { showExportLedgerDialog = false },
                    title = { Text("Export CSV/PDF by Ledger", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    text = {
                        Column {
                            Text("Select specific account ledger to export transaction history:", fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(10.dp))

                            // Ledger Dropdown / Radio selection
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Choose Ledger Account:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                    Spacer(modifier = Modifier.height(6.dp))

                                    val ledgerList = remember(trialBalance) {
                                        listOf("ALL LEDGERS", "Cash Account", "Bank Account") + trialBalance.map { it.name }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        ledgerList.distinct().take(10).forEach { lName ->
                                            FilterChip(
                                                selected = selectedLedgerForExport == lName,
                                                onClick = { selectedLedgerForExport = lName },
                                                label = { Text(lName, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = RoyalPurplePrimary,
                                                    selectedLabelColor = Color.White
                                                )
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
                                val filteredVouchers = if (selectedLedgerForExport == "ALL LEDGERS") {
                                    allVouchers
                                } else {
                                    allVouchers.filter { it.partyName.equals(selectedLedgerForExport, ignoreCase = true) }
                                }
                                val csvText = com.example.utils.CsvExporter.generateTransactionHistoryCsv(filteredVouchers)
                                com.example.utils.CsvExporter.shareCsvFile(context, "${selectedLedgerForExport.replace(" ", "_")}_Export.csv", csvText)
                                showExportLedgerDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Export CSV/PDF")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExportLedgerDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("settings_export_signature_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "EXPORT TOOLS & DIGITAL SIGNATURE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Export specific ledger statements in CSV/PDF format and set up digital seal signature.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showExportLedgerDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export CSV/PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showSignaturePadDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Signature Pad", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Tally Prime & Marg ERP Integration Card (Moved from Dashboard)
        item {
            val allVouchers by viewModel.vouchersState.collectAsState()

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("settings_tally_marg_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "TALLY PRIME & MARG ERP INTEGRATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Export accounting entries to Tally XML / Marg CSV formats or sync master accounts.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val xml = com.example.utils.TallyMargXmlUtil.exportToTallyXml(allVouchers, user)
                                com.example.utils.CsvExporter.shareCsvFile(context, "Tally_Import_Vouchers.xml", xml)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export Tally XML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val xml = com.example.utils.TallyMargXmlUtil.exportToMargXml(allVouchers, user)
                                com.example.utils.CsvExporter.shareCsvFile(context, "Marg_Import_Vouchers.xml", xml)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export Marg ERP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Module C: JSON Telemetry, Crash & Analytics Engine Card
        item {
            var showJsonModalText by remember { mutableStateOf<String?>(null) }
            var jsonTitleText by remember { mutableStateOf("JSON Telemetry Payload") }

            if (showJsonModalText != null) {
                AlertDialog(
                    onDismissRequest = { showJsonModalText = null },
                    title = { Text(jsonTitleText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    text = {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                            modifier = Modifier.fillMaxWidth().height(280.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = showJsonModalText!!,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = Color(0xFF00FF88),
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                com.example.utils.CsvExporter.shareCsvFile(
                                    context,
                                    "Telemetry_Payload_${System.currentTimeMillis()}.json",
                                    showJsonModalText!!
                                )
                                showJsonModalText = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share JSON Payload")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showJsonModalText = null }) {
                            Text("Close")
                        }
                    }
                )
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("telemetry_json_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "JSON TELEMETRY, CRASH & ANALYTICS ENGINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Real-time JSON analytics suite for app feature engagement, database query latency, and system crash diagnostics.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                jsonTitleText = "User Analytics JSON Report"
                                com.example.utils.TelemetryEngine.trackFeatureEngagement("AnalyticsReportViewed")
                                showJsonModalText = com.example.utils.TelemetryEngine.generateAnalyticsReportJson()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("User Analytics JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                jsonTitleText = "Crash Diagnostics JSON Report"
                                showJsonModalText = com.example.utils.TelemetryEngine.getLatestCrashLogJson()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Crash Diagnostics JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    var maintenanceStatus by remember { mutableStateOf("") }
                    var apiSyncStatus by remember { mutableStateOf("") }
                    val coroutineScope = rememberCoroutineScope()

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                maintenanceStatus = "Running 30-day Room storage optimization..."
                                val res = com.example.utils.TelemetryMaintenanceEngine.runMaintenance(context, 30)
                                maintenanceStatus = if (res.isSuccess) {
                                    val r = res.getOrNull()
                                    "Optimization Complete: ${r?.deletedCount ?: 0} logs purged, ${r?.monthBucketsProcessed ?: 0} monthly buckets updated."
                                } else {
                                    "Optimization Error: ${res.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("run_telemetry_maintenance_btn")
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Optimize Database Storage (Archive >30 Days)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                apiSyncStatus = "Syncing Room telemetry streams to /api/v1/telemetry/crash-log with RetryInterceptor..."
                                val repo = com.example.data.repository.TelemetrySyncRepository(context)
                                val res = repo.syncLocalCrashLogsToRemote()
                                apiSyncStatus = if (res.isSuccess) {
                                    "REST API Sync Complete: ${res.getOrNull()?.message}"
                                } else {
                                    "REST API Sync Result: ${res.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        modifier = Modifier.fillMaxWidth().testTag("sync_telemetry_retrofit_btn")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Room Telemetry to REST API (Retrofit + Retry)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (maintenanceStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = maintenanceStatus,
                            fontSize = 11.sp,
                            color = AccountingGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (apiSyncStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = apiSyncStatus,
                            fontSize = 11.sp,
                            color = RoyalPurplePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Firebase Firestore Cloud Sync Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("cloud_sync_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FIREBASE CLOUD SYNC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoyalPurplePrimary,
                            letterSpacing = 1.sp
                        )
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = RoyalPurplePrimary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Synchronize local SQLite books with Firestore cloud to access and restore on multiple mobile devices.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (syncStatusMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = syncStatusMsg!!,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RoyalPurplePrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.syncDataToFirestore() },
                            enabled = !isSyncing,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Sync Books", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.restoreDataFromFirestore() },
                            enabled = !isSyncing,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore Books", fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Background Ledger Reconciliation Worker Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("ledger_reconciliation_settings_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AUTOMATED LEDGER RECONCILIATION",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "WorkManager worker matching invoice amounts against receipts",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoReconciliationEnabled,
                            onCheckedChange = { viewModel.toggleAutoReconciliation(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = RoyalPurplePrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (unresolvedDiscrepancyCount > 0) "$unresolvedDiscrepancyCount discrepancy flag(s) active" else "Ledgers cleanly reconciled",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (unresolvedDiscrepancyCount > 0) AccountingRed else AccountingGreen
                            )
                            Text(
                                text = "Last Scan: $lastReconciliationTime",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { showReconciliationModal = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.testTag("open_reconciliation_modal_settings_btn")
                        ) {
                            Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Service", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Automatic Offline Room Database Cloud Backup Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("offline_cloud_backup_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "AUTOMATIC ON-DEVICE BACKUP",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Writes a full backup of your books to this device's storage. Use Sync Books for cloud.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoCloudBackup,
                            onCheckedChange = { enabled ->
                                viewModel.setCloudBackupSettings(context, enabled, backupFrequency)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Backup Interval:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly").forEach { (freqKey, label) ->
                            FilterChip(
                                selected = backupFrequency == freqKey,
                                onClick = { viewModel.setCloudBackupSettings(context, autoCloudBackup, freqKey) },
                                label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RoyalPurplePrimary,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Last Backup Status:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(lastBackupTime, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                        }
                        Button(
                            onClick = { viewModel.triggerManualCloudBackup() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Back Up Now", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // The retrieval half of the backup feature. Backups have been written
                    // to this directory all along with no way to ever read one back —
                    // a backup you cannot restore from is not a backup.
                    OutlinedButton(
                        onClick = {
                            viewModel.loadDeviceBackups { files ->
                                deviceBackups = files
                                showRestoreBackupDialog = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore From a Backup on This Device", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Import & Export Data
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "DATA BACKUP & RECOVERY (JSON)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Offline-first JSON backup. Save ledgers, vouchers, items to storage or restore anytime.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showImportJsonDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import JSON")
                        }
                        Button(
                            onClick = {
                                viewModel.exportDataToJson { json ->
                                    exportedJsonText = json
                                    showExportJsonDialog = true
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export JSON")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Single-Click Logout Button
        item {
            Button(
                onClick = { viewModel.logout() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HardRedDelete),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("single_click_logout_button")
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout Account", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }

    // Add Stock Item Modal
    if (showAddItemDialog) {
        AddStockItemDialog(
            onDismiss = { showAddItemDialog = false },
            onConfirm = { name, unit, hsn, gstRate, cost, price, openingQty ->
                viewModel.addInventoryItem(name, unit, hsn, gstRate, cost, price, openingQty)
                showAddItemDialog = false
            }
        )
    }

    // Edit Profile Modal
    if (showEditProfileDialog) {
        EditProfileDialog(
            currentUser = user,
            onDismiss = { showEditProfileDialog = false },
            onSave = { updatedUser ->
                viewModel.updateUserProfile(updatedUser)
                showEditProfileDialog = false
            }
        )
    }

    // Voucher Type Management Modal
    if (showVoucherTypeManagementModal) {
        com.example.ui.components.VoucherTypeManagementModal(
            viewModel = viewModel,
            onDismiss = { showVoucherTypeManagementModal = false }
        )
    }

    // Ledger Reconciliation Modal
    if (showReconciliationModal) {
        com.example.ui.components.LedgerReconciliationModal(
            discrepancies = reconciliationDiscrepancies,
            unresolvedCount = unresolvedDiscrepancyCount,
            isReconciling = isReconciling,
            autoReconciliationEnabled = autoReconciliationEnabled,
            lastReconciliationTime = lastReconciliationTime,
            onDismiss = { showReconciliationModal = false },
            onRunReconciliationNow = { viewModel.triggerAutoReconciliation() },
            onResolveDiscrepancy = { id, notes -> viewModel.resolveDiscrepancy(id, notes) },
            onToggleAutoReconciliation = { enabled -> viewModel.toggleAutoReconciliation(enabled) }
        )
    }

    // GST Certificate Modal
    if (showGstCertificateModal) {
        GstCertificateModal(
            user = user,
            onDismiss = { showGstCertificateModal = false }
        )
    }

    // Export JSON Backup Modal
    if (showExportJsonDialog) {
        AlertDialog(
            onDismissRequest = { showExportJsonDialog = false },
            title = { Text("Exported JSON Database Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A complete backup of your books — ledgers, groups, vouchers, journal " +
                            "entries, stock, GST details and voucher numbering. Copy it or save " +
                            "the file somewhere safe; it can be pasted back under Import JSON.",
                        fontSize = 12.sp
                    )
                    if (exportShareFailed) {
                        Text(
                            "Could not open the share sheet — the backup was not shared. " +
                                "Use Copy JSON instead.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccountingRed
                        )
                    }
                    OutlinedTextField(
                        value = exportedJsonText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("TallyBackupJSON", exportedJsonText)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "JSON Backup copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Copy JSON", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            val fileName = "BillShield_Books_Backup_${System.currentTimeMillis()}.json"
                            // Only close the dialog if the share actually started. It used
                            // to close unconditionally, on top of a success message emitted
                            // before the file was even written — so a failed share left the
                            // user with "backed up successfully" and no file.
                            val shared = com.example.utils.CsvExporter
                                .shareCsvFile(context, fileName, exportedJsonText)
                            if (shared) {
                                showExportJsonDialog = false
                            } else {
                                exportShareFailed = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Backup File", fontSize = 11.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportJsonDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Import JSON Backup Modal
    if (showImportJsonDialog) {
        AlertDialog(
            onDismissRequest = { showImportJsonDialog = false },
            title = { Text("Import JSON Database Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste a backup file's contents below. This REPLACES your current books — " +
                            "every voucher, ledger and stock item is overwritten by what the backup contains. " +
                            "If the file cannot be read, nothing is changed.",
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        placeholder = { Text("Paste JSON backup string here...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importJsonText.isNotBlank()) {
                            viewModel.importDataFromJson(importJsonText) { success ->
                                if (success) {
                                    showImportJsonDialog = false
                                    importJsonText = ""
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Restore Database")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportJsonDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRestoreBackupDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreBackupDialog = false },
            title = { Text("Restore From Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                if (deviceBackups.isEmpty()) {
                    Text(
                        "No backups found on this device yet. Use \"Back Up Now\", or turn on " +
                            "automatic backup above.",
                        fontSize = 12.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Restoring REPLACES your current books entirely. Pick a backup:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        deviceBackups.take(10).forEach { file ->
                            Card(
                                onClick = { backupPendingConfirm = file },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${com.example.service.BookBackupStore.humanReadableSize(file)} · " +
                                            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.ENGLISH)
                                                .format(java.util.Date(file.lastModified())),
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
                TextButton(onClick = { showRestoreBackupDialog = false }) { Text("Close") }
            }
        )
    }

    // Restoring destroys the current books, so it takes a second, explicit confirmation
    // naming the file — the same standard the delete-voucher flow already applies.
    backupPendingConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { backupPendingConfirm = null },
            title = { Text("Replace your books?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    "Every voucher, ledger and stock item currently in the app will be replaced " +
                        "by the contents of ${file.name}. This cannot be undone.",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreFromDeviceBackup(file) { success ->
                            backupPendingConfirm = null
                            if (success) showRestoreBackupDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingRed)
                ) { Text("Replace My Books") }
            },
            dismissButton = {
                TextButton(onClick = { backupPendingConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun EditProfileDialog(
    currentUser: UserEntity,
    onDismiss: () -> Unit,
    onSave: (UserEntity) -> Unit
) {
    var businessName by remember { mutableStateOf(currentUser.businessName) }
    var firstName by remember { mutableStateOf(currentUser.firstName) }
    var middleName by remember { mutableStateOf(currentUser.middleName) }
    var surname by remember { mutableStateOf(currentUser.surname) }
    var fatherName by remember { mutableStateOf(currentUser.fatherName) }
    var dob by remember { mutableStateOf(currentUser.dob) }
    var dod by remember { mutableStateOf(currentUser.dod) }
    var phone by remember { mutableStateOf(currentUser.phoneNumber) }
    var email by remember { mutableStateOf(currentUser.email) }
    var gstin by remember { mutableStateOf(currentUser.gstin) }
    var address by remember { mutableStateOf(currentUser.address) }
    var pincode by remember { mutableStateOf(currentUser.pincode) }
    var city by remember { mutableStateOf(currentUser.city) }
    var state by remember { mutableStateOf(currentUser.state) }
    var isFetchingPin by remember { mutableStateOf(false) }
    var pinMessage by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Business & Personal Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Business Details", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                    OutlinedTextField(
                        value = businessName,
                        onValueChange = { businessName = it },
                        label = { Text("Business Trade Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it.uppercase() },
                        label = { Text("GSTIN Number (15 Digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Personal Profile Info", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            label = { Text("First Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = middleName,
                            onValueChange = { middleName = it },
                            label = { Text("Middle") },
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = surname,
                            onValueChange = { surname = it },
                            label = { Text("Surname") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = fatherName,
                        onValueChange = { fatherName = it },
                        label = { Text("Full Father Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = dob,
                            onValueChange = { dob = it },
                            label = { Text("DOB (dd/mm/yyyy)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = dod,
                            onValueChange = { dod = it },
                            label = { Text("DOD (dd/mm/yyyy)") },
                            placeholder = { Text("Optional") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Contact & Address", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                }

                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Mobile Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Street Address / Building") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Pincode with Auto Lookup
                item {
                    Column {
                        OutlinedTextField(
                            value = pincode,
                            onValueChange = { inputPin ->
                                pincode = inputPin
                                if (inputPin.trim().length == 6) {
                                    isFetchingPin = true
                                    pinMessage = "Fetching location from postal API..."
                                    coroutineScope.launch {
                                        val res = PostalPincodeService.fetchPincodeDetails(inputPin)
                                        isFetchingPin = false
                                        if (res.isSuccess) {
                                            if (res.district.isNotBlank()) city = res.district
                                            if (res.state.isNotBlank()) state = res.state
                                            pinMessage = "Location auto-filled: ${res.district}, ${res.state}"
                                        } else {
                                            pinMessage = res.errorMessage ?: "Pincode not found"
                                        }
                                    }
                                } else {
                                    pinMessage = ""
                                }
                            },
                            label = { Text("Pincode (6 Digits)") },
                            singleLine = true,
                            trailingIcon = {
                                if (isFetchingPin) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinMessage.isNotBlank()) {
                            Text(
                                text = pinMessage,
                                fontSize = 11.sp,
                                color = RoyalPurplePrimary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("City/District") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state,
                            onValueChange = { state = it },
                            label = { Text("State") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val fullOwnerName = "$firstName $middleName $surname".replace("\\s+".toRegex(), " ").trim()
                    val updated = currentUser.copy(
                        businessName = businessName.trim(),
                        firstName = firstName.trim(),
                        middleName = middleName.trim(),
                        surname = surname.trim(),
                        fatherName = fatherName.trim(),
                        dob = dob.trim(),
                        dod = dod.trim(),
                        ownerName = fullOwnerName,
                        phoneNumber = phone.trim(),
                        email = email.trim(),
                        gstin = gstin.trim(),
                        address = address.trim(),
                        pincode = pincode.trim(),
                        city = city.trim(),
                        state = state.trim()
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
            ) {
                Text("Save Profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun GstCertificateModal(
    user: UserEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = RoyalPurplePrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("GST Registration Certificate", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Government of India", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(6.dp), color = AccountingGreen.copy(alpha = 0.15f)) {
                            Text(user.gstStatus, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingGreen, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }

                    Divider()

                    Text("Registration Number (GSTIN):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.gstin, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Legal / Trade Name:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.businessName, fontSize = 15.sp, fontWeight = FontWeight.Bold)

                    Text("Proprietor Name:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.ownerName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                    Text("Principal Place of Business:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${user.address}, ${user.city}, ${user.state} - ${user.pincode}", fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Constitution of Business:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(user.constitutionOfBusiness, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Date of Liability:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(user.gstRegistrationDate, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)) {
                Text("Close Certificate")
            }
        }
    )
}

@Composable
fun AddStockItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("Pcs") }
    // Was prefilled "8471" (computer hardware) and "18" — a plausible HSN code and rate
    // silently attached to whatever the user was actually creating, and carried into
    // GST returns from there.
    var hsn by remember { mutableStateOf("") }
    var gstRate by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var openingQty by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Stock Inventory Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Item Name") }, singleLine = true)
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit (Pcs, Box, Kg, Nos)") }, singleLine = true)
                OutlinedTextField(value = hsn, onValueChange = { hsn = it }, label = { Text("HSN Code") }, singleLine = true)
                OutlinedTextField(value = gstRate, onValueChange = { gstRate = it }, label = { Text("GST Rate (%)") }, singleLine = true)
                OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("Cost Price (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Selling Price (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                // Stock already on the shelf. This field did not exist and the quantity
                // was hardcoded to zero, so an existing business could not record what it
                // held — which is why the first sale of any item drove stock negative.
                OutlinedTextField(
                    value = openingQty,
                    onValueChange = { openingQty = it },
                    label = { Text("Opening Quantity (already in stock)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, unit, hsn, gstRate, cost, price, openingQty) },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
            ) {
                Text("Save Item")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
