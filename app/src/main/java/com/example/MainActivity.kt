package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.components.AccountingBottomNavBar
import com.example.ui.components.NavItem
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collectLatest

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.example.ui.theme.OnAccent
import com.example.ui.theme.RoyalPurplePrimary

class MainActivity : ComponentActivity() {

    private val viewModel: AccountingViewModel by viewModels()
    private val actionState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        actionState.value = intent?.getStringExtra("ACTION")

        setContent {
            val themeMode by viewModel.themeModeState.collectAsState()
            val dynamicColor by viewModel.dynamicColorState.collectAsState()

            MyApplicationTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                val context = LocalContext.current
                val userState by viewModel.userState.collectAsState()
                val allLedgers by viewModel.ledgersState.collectAsState()
                var currentRoute by remember { mutableStateOf(NavItem.Dashboard.route) }
                var activeVoucherType by remember { mutableStateOf(VoucherType.SALES) }
                var gstPrefill by remember { mutableStateOf<com.example.ui.screens.VoucherGstPrefill?>(null) }

                LaunchedEffect(Unit) {
                    viewModel.messageEvent.collectLatest { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                val currentAction = actionState.value
                LaunchedEffect(currentAction) {
                    when (currentAction) {
                        "NEW_VOUCHER", "NEW_SALE" -> {
                            activeVoucherType = VoucherType.SALES
                            currentRoute = NavItem.Vouchers.route
                            actionState.value = null
                        }
                        "NEW_PURCHASE" -> {
                            activeVoucherType = VoucherType.PURCHASE
                            currentRoute = NavItem.Vouchers.route
                            actionState.value = null
                        }
                        "CHECK_BALANCE" -> {
                            currentRoute = NavItem.Dashboard.route
                            actionState.value = null
                        }
                    }
                }

                if (userState == null || !userState!!.isLoggedIn) {
                    AuthScreen(viewModel = viewModel)
                } else {
                    val user = userState!!

                    var showMainQrScanner by remember { mutableStateOf(false) }

                    if (showMainQrScanner) {
                        com.example.ui.components.QrCodeScannerDialog(
                            realLedgers = allLedgers,
                            initialVoucherType = activeVoucherType,
                            onDismissRequest = { showMainQrScanner = false },
                            onInvoiceScanned = { scannedData ->
                                viewModel.addVoucher(
                                    type = scannedData.voucherType,
                                    partyName = scannedData.partyName,
                                    amountText = scannedData.amount.toString(),
                                    gstRateText = scannedData.gstRate.toString(),
                                    isInterstate = scannedData.isInterstate,
                                    narration = scannedData.narration
                                )
                            }
                        )
                    }

                    // One list state per FAB-bearing screen, owned here because the
                    // Scaffold's FAB is what reacts to it.
                    val dashboardListState = rememberLazyListState()
                    val reportsListState = rememberLazyListState()

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        floatingActionButton = {
                            val onDashboard = currentRoute == NavItem.Dashboard.route
                            val onReports = currentRoute == NavItem.Reports.route
                            if (onDashboard || onReports) {
                                // Extended only at the top of the list, an icon once
                                // scrolled. Permanently extended, it is ~200dp wide and
                                // parked over the content: on the dashboard it covered two
                                // of the four Quick Action tiles, and on Reports the net
                                // profit figure. Collapsing on scroll is the Material
                                // behaviour for exactly this reason.
                                val listState = if (onDashboard) dashboardListState else reportsListState
                                val atTop by remember(listState) {
                                    derivedStateOf { !listState.canScrollBackward }
                                }
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        activeVoucherType = VoucherType.SALES
                                        currentRoute = NavItem.Vouchers.route
                                    },
                                    expanded = atTop,
                                    icon = { Icon(Icons.Default.Add, contentDescription = "New Voucher") },
                                    text = { Text("New Voucher", fontWeight = FontWeight.Bold) },
                                    containerColor = RoyalPurplePrimary,
                                    contentColor = OnAccent,
                                    modifier = Modifier.testTag("shortcut_new_voucher_fab")
                                )
                            }
                        },
                        bottomBar = {
                            AccountingBottomNavBar(
                                selectedRoute = currentRoute,
                                onTabSelected = { route ->
                                    if (route == NavItem.Scan.route) {
                                        showMainQrScanner = true
                                    } else {
                                        currentRoute = route
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            when (currentRoute) {
                                NavItem.Dashboard.route -> DashboardScreen(
                                    viewModel = viewModel,
                                    user = user,
                                    listState = dashboardListState,
                                    onNavigateToVouchers = { vType ->
                                        // Cleared so a prefill from an earlier calculator
                                        // run cannot re-seed an unrelated later entry.
                                        gstPrefill = null
                                        activeVoucherType = vType
                                        currentRoute = NavItem.Vouchers.route
                                    },
                                    onNavigateToVouchersWithGst = { vType, prefill ->
                                        gstPrefill = prefill
                                        activeVoucherType = vType
                                        currentRoute = NavItem.Vouchers.route
                                    }
                                )
                                NavItem.Vouchers.route -> VouchersScreen(
                                    viewModel = viewModel,
                                    user = user,
                                    initialVoucherType = activeVoucherType,
                                    gstPrefill = gstPrefill
                                )
                                NavItem.Reports.route -> ReportsScreen(
                                    viewModel = viewModel,
                                    user = user,
                                    listState = reportsListState
                                )
                                NavItem.Settings.route -> SettingsScreen(
                                    viewModel = viewModel,
                                    user = user
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        actionState.value = intent.getStringExtra("ACTION")
    }
}
