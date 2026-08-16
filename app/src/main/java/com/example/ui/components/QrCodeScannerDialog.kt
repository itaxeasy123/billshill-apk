package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.ocr.InvoiceImportResult
import com.example.data.ocr.OcrInvoiceClient
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.VoucherType
import com.example.ui.theme.ConsoleAccent
import com.example.ui.theme.ConsoleBackdrop
import com.example.ui.theme.ConsolePanel
import com.example.ui.theme.MutedText
import com.example.ui.theme.MutedTextSoft
import com.example.ui.theme.OnDarkPanel
import com.example.ui.theme.OnNeon
import com.example.ui.theme.ScannerScrim
import com.example.ui.theme.SubtleBorder
import com.example.ui.theme.WarnAmberStatus

import com.example.data.model.LedgerEntity

data class ScannedInvoiceData(
    val partyName: String,
    val amount: Double,
    val gstRate: Double,
    val isInterstate: Boolean,
    val narration: String,
    val voucherType: VoucherType
)

@Composable
fun QrCodeScannerDialog(
    onDismissRequest: () -> Unit,
    onInvoiceScanned: (ScannedInvoiceData) -> Unit,
    realLedgers: List<LedgerEntity> = emptyList(),
    /** Seeds the type chips from whatever the calling screen is already working on. */
    initialVoucherType: VoucherType = VoucherType.PURCHASE
) {
    // Every field starts empty. They used to open pre-filled with an invented invoice --
    // party "Cash Account", amount 15000, rate 18%, narration "Scanned Invoice OCR Entry"
    // -- so a single tap on Save posted a ₹15,000 purchase the user never made. There is
    // no camera binding and no OCR in this dialog; nothing here was ever scanned.
    var customPartyName by remember { mutableStateOf("") }
    var customAmountText by remember { mutableStateOf("") }
    var customGstRateText by remember { mutableStateOf("") }
    var customVoucherType by remember { mutableStateOf(initialVoucherType) }
    var isInterstate by remember { mutableStateOf(false) }

    // Invoice import. The extracted values FILL the form rather than posting anything —
    // the user confirms before saving, because an extracted figure is a suggestion and
    // this app's whole failure mode is wrong numbers that balance.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        importMessage = null
        scope.launch {
            when (val r = OcrInvoiceClient.importInvoice(context, uri)) {
                is InvoiceImportResult.Extracted -> {
                    customPartyName = r.partyName
                    customAmountText = r.amountInclusive.toString()
                    customGstRateText = if (r.gstRate % 1.0 == 0.0) {
                        r.gstRate.toInt().toString()
                    } else {
                        r.gstRate.toString()
                    }
                    isInterstate = r.isInterstate
                    customVoucherType = r.voucherType
                    importMessage = buildString {
                        append("Read ")
                        append(r.invoiceNo ?: "invoice")
                        append(" — check the figures below before saving.")
                    }
                }
                is InvoiceImportResult.Failed -> importMessage = r.reason
            }
            isImporting = false
        }
    }

    // Only real ledgers from the database. The fallback list here previously invented four
    // party accounts ("Sharma Electronics", "Apex Wholesale", ...) under a heading that
    // called them REAL LEDGERS.
    // Parties only. The caller hands over every ledger in the book, which includes
    // "Output CGST", "Sales Account" and "Difference in Opening Balances" — suggesting
    // those as a counterparty puts a tax-liability ledger one tap from being credited as
    // a vendor. Party ledgers are the ones getOrCreatePartyLedger files under Sundry
    // Debtors / Sundry Creditors, and system ledgers always carry a systemCode.
    val realParties = remember(realLedgers) {
        realLedgers.filter {
            it.systemCode == null &&
                (it.groupName.contains("Sundry", ignoreCase = true) ||
                    it.groupName.contains("Debtor", ignoreCase = true) ||
                    it.groupName.contains("Creditor", ignoreCase = true))
        }.map { it.name }
    }

    val parsedAmount = customAmountText.trim().toDoubleOrNull()
    val parsedGstRate = customGstRateText.trim().toDoubleOrNull()
    val canSubmit = customPartyName.isNotBlank() &&
        parsedAmount != null && parsedAmount > 0.0 &&
        parsedGstRate != null && parsedGstRate >= 0.0

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ScannerScrim)
                .testTag("qr_code_scanner_dialog"),
            color = ScannerScrim
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Simulated Camera Viewfinder Canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ConsoleBackdrop)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Header Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismissRequest,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(OnDarkPanel.copy(alpha = 0.2f))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Scanner", tint = OnDarkPanel)
                            }

                            Text(
                                text = "Enter Invoice Details",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnDarkPanel
                            )

                            // The flash toggle was removed along with the laser animation: both
                            // simulated a live camera this dialog does not have.
                            Spacer(modifier = Modifier.size(48.dp))
                        }

                        // Was a 280dp frame with four green corner brackets drawn to look
                        // like a camera viewfinder, over a dialog with no camera bound to
                        // it. While Save was disabled that was a cosmetic lie; once Save
                        // began posting to the ledger it manufactured false provenance —
                        // typed data framed as machine-verified, so nobody proofreads it.
                        //
                        // What replaces it does the thing the frame implied: picks an
                        // invoice PDF and fills the form from it.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                                enabled = !isImporting,
                                colors = ButtonDefaults.buttonColors(containerColor = ConsoleAccent),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isImporting) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = OnNeon,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Reading invoice…", color = OnNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                } else {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = OnNeon, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Import invoice PDF", color = OnNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = importMessage
                                    ?: "Reads a BillShield invoice PDF and fills the fields below. Check them before saving.",
                                fontSize = 10.sp,
                                color = if (importMessage != null) WarnAmberStatus else MutedTextSoft.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Real OCR Scan Confirmation & Party Selector Card
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = ConsolePanel),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // Heading previously read "SCANNED INVOICE DATA (REAL LEDGERS)"
                                // above a hardcoded list of invented parties. Nothing is scanned
                                // and nothing is pre-filled; this is a manual entry form.
                                Text(
                                    text = "INVOICE DETAILS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ConsoleAccent
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Party: free text, with existing ledgers offered as
                                // suggestions.
                                //
                                // This used to be chips ONLY, rendered from a `realLedgers`
                                // parameter that defaulted to empty and that no call site
                                // ever passed. Since the chips were the only writer of
                                // customPartyName, the name could never become non-blank,
                                // canSubmit could never become true, and the Save button was
                                // permanently disabled beneath a hint reading "Enter a party"
                                // with nowhere to enter one.
                                //
                                // Passing the ledgers would not have been enough: a fresh
                                // book contains only seeded system accounts (Cash in Hand,
                                // Sales Account, Output CGST...) and not one customer, so a
                                // picker would offer thirteen wrong answers and still no way
                                // to name a new party. Every other party input in this app is
                                // free text backed by getOrCreatePartyLedger, which creates
                                // the ledger on demand; this is now the same.
                                OutlinedTextField(
                                    value = customPartyName,
                                    onValueChange = { customPartyName = it },
                                    label = { Text("Party Name", fontSize = 10.sp, color = MutedTextSoft) },
                                    placeholder = { Text("Customer or supplier", fontSize = 11.sp, color = MutedText) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ConsoleAccent,
                                        unfocusedBorderColor = SubtleBorder,
                                        focusedTextColor = OnDarkPanel,
                                        unfocusedTextColor = OnDarkPanel,
                                        cursorColor = ConsoleAccent
                                    )
                                )

                                val suggestions = remember(realParties, customPartyName) {
                                    if (customPartyName.isBlank()) realParties.take(8)
                                    else realParties.filter {
                                        it.contains(customPartyName, ignoreCase = true) &&
                                            !it.equals(customPartyName, ignoreCase = true)
                                    }.take(8)
                                }
                                if (suggestions.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        suggestions.forEach { party ->
                                            FilterChip(
                                                selected = customPartyName == party,
                                                onClick = { customPartyName = party },
                                                label = { Text(party, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = ConsoleAccent,
                                                    selectedLabelColor = OnNeon,
                                                    containerColor = OnDarkPanel.copy(alpha = 0.1f),
                                                    labelColor = OnDarkPanel
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Voucher type and place of supply both had NO writer: every
                                // entry posted as an intrastate PURCHASE regardless of what
                                // it actually was. The type decides which group the party
                                // ledger is created under, and interstate decides IGST vs
                                // CGST+SGST, so neither could stay hardwired.
                                Text("Voucher Type:", fontSize = 11.sp, color = MutedTextSoft)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // SALES and PURCHASE only. RECEIPT/PAYMENT/CONTRA/JOURNAL
                                    // post no tax legs, but createVoucher still writes a
                                    // gst_tax_details row whenever the rate is non-zero — the
                                    // wizard guards this by forcing the rate to 0 for those
                                    // types, and a Rs 7,000 Contra once posted as Rs 8,260 with
                                    // a phantom Rs 1,260 tax row. This form is for invoices.
                                    listOf(VoucherType.PURCHASE, VoucherType.SALES).forEach { type ->
                                        FilterChip(
                                            selected = customVoucherType == type,
                                            onClick = { customVoucherType = type },
                                            label = { Text(type.name, fontSize = 10.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ConsoleAccent,
                                                selectedLabelColor = OnNeon,
                                                containerColor = OnDarkPanel.copy(alpha = 0.1f),
                                                labelColor = OnDarkPanel
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Place of supply:",
                                        fontSize = 11.sp,
                                        color = MutedTextSoft
                                    )
                                    FilterChip(
                                        selected = !isInterstate,
                                        onClick = { isInterstate = false },
                                        label = { Text("Intra-State", fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ConsoleAccent,
                                            selectedLabelColor = OnNeon,
                                            containerColor = OnDarkPanel.copy(alpha = 0.1f),
                                            labelColor = OnDarkPanel
                                        )
                                    )
                                    FilterChip(
                                        selected = isInterstate,
                                        onClick = { isInterstate = true },
                                        label = { Text("Inter-State", fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ConsoleAccent,
                                            selectedLabelColor = OnNeon,
                                            containerColor = OnDarkPanel.copy(alpha = 0.1f),
                                            labelColor = OnDarkPanel
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customAmountText,
                                        onValueChange = { customAmountText = it },
                                        label = { Text("Invoice Amount (₹)", fontSize = 10.sp, color = MutedTextSoft) },
                                        placeholder = { Text("0.00", fontSize = 11.sp, color = MutedText) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ConsoleAccent,
                                            unfocusedBorderColor = SubtleBorder,
                                            focusedTextColor = OnDarkPanel,
                                            unfocusedTextColor = OnDarkPanel
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )

                                    OutlinedTextField(
                                        value = customGstRateText,
                                        onValueChange = { customGstRateText = it },
                                        label = { Text("GST Rate (%)", fontSize = 10.sp, color = MutedTextSoft) },
                                        placeholder = { Text("e.g. 18", fontSize = 11.sp, color = MutedText) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ConsoleAccent,
                                            unfocusedBorderColor = SubtleBorder,
                                            focusedTextColor = OnDarkPanel,
                                            unfocusedTextColor = OnDarkPanel
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Submission is blocked until the party, amount and rate are all
                                // actually entered. It used to substitute ₹1,000 and 18% for
                                // anything the user left unparseable.
                                Button(
                                    onClick = {
                                        val amt = parsedAmount
                                        val gst = parsedGstRate
                                        if (amt != null && gst != null) {
                                            onInvoiceScanned(
                                                ScannedInvoiceData(
                                                    partyName = customPartyName,
                                                    amount = amt,
                                                    gstRate = gst,
                                                    isInterstate = isInterstate,
                                                    narration = "",
                                                    voucherType = customVoucherType
                                                )
                                            )
                                            onDismissRequest()
                                        }
                                    },
                                    enabled = canSubmit,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ConsoleAccent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Save Voucher", color = OnNeon, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }

                                if (!canSubmit) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Enter a party, an amount and a GST rate to save.",
                                        fontSize = 10.sp,
                                        color = MutedTextSoft.copy(alpha = 0.7f)
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
