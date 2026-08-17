package com.example.invoice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.example.data.db.AppDatabase
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one invoice surface.
 *
 * Every place that used to render, preview, print or share a document now opens this — the
 * per-voucher PDF button, the statement's voucher drill-down, the daybook's Print Bill —
 * so all nine voucher types get the same document, and a Credit Note is no longer sent out
 * headed "ACCOUNTING VOUCHER".
 *
 * Assembles the document off the main thread, then hands it to [InvoiceEditorDialog].
 */
@Composable
fun VoucherInvoiceDialog(
    voucher: VoucherEntity,
    user: UserEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InvoiceBrandingStore(context) }

    // Null until DataStore has actually produced the stored style. Seeding this with a
    // default instead meant the editor could open on factory styling and write that back
    // over the user's own before the real value arrived.
    val branding by store.brandingFlow.collectAsState(initial = null)

    var baseDoc by remember(voucher.id) { mutableStateOf<InvoiceDocument?>(null) }

    LaunchedEffect(voucher.id, user) {
        baseDoc = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).accountingDao()
            InvoiceAssembler.assemble(dao, voucher, user)
        }
    }

    val doc = baseDoc
    val style = branding
    if (doc == null || style == null) {
        // The item rows and party ledger are a database read; showing the spinner in the
        // dialog rather than blocking the tap keeps the button feeling responsive.
        Dialog(onDismissRequest = onDismiss) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    InvoiceEditorDialog(
        baseDoc = doc,
        branding = style,
        voucherId = voucher.id,
        onBrandingChange = { updated ->
            if (updated != style) {
                scope.launch { store.save(updated) }
            }
        },
        onDismiss = onDismiss
    )
}
