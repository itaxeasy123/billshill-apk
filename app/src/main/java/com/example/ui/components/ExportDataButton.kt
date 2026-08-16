package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AccountingViewModel
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.CsvExporter

@Composable
fun ExportDataButton(
    viewModel: AccountingViewModel,
    // Just "Export". The button opens a menu of export formats, so the long
    // per-tab names ("Export B/S Data") said nothing the surrounding heading
    // did not already say -- and they wrapped to two lines inside the button.
    label: String = "Export",
    modifier: Modifier = Modifier,
    isOutlined: Boolean = false
) {
    val context = LocalContext.current
    val vouchers by viewModel.vouchersState.collectAsState()
    val trialBalance by viewModel.trialBalanceState.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (isOutlined) {
            OutlinedButton(
                onClick = { showMenu = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("export_data_outlined_btn")
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        } else {
            Button(
                onClick = { showMenu = true },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("export_data_primary_btn")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Export Voucher Transactions (CSV)") },
                onClick = {
                    showMenu = false
                    val csv = CsvExporter.generateTransactionHistoryCsv(vouchers)
                    CsvExporter.shareCsvFile(
                        context,
                        "Vouchers_Export_${System.currentTimeMillis()}.csv",
                        csv
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("Export Account Aggregates & Ledgers (CSV)") },
                onClick = {
                    showMenu = false
                    val csv = CsvExporter.generateLedgerSummaryCsv(trialBalance)
                    CsvExporter.shareCsvFile(
                        context,
                        "Ledger_Aggregates_Export_${System.currentTimeMillis()}.csv",
                        csv
                    )
                }
            )
        }
    }
}
