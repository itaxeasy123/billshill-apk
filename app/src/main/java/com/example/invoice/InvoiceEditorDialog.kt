package com.example.invoice

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.components.ChoiceChip
import com.example.ui.components.ChoiceChipRow
import com.example.ui.components.dialogSystemBarInsets
import com.example.ui.theme.OnAccent
import com.example.ui.theme.RoyalPurplePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * The invoice, as the user will send it, with the controls that restyle it.
 *
 * Every export path in the app opens this: the document is previewed before it leaves,
 * and the same screen that previews it is the one that customises it, so there is no
 * separate settings page to hunt for and no way to discover a wrong logo only after the
 * customer has the PDF.
 *
 * Changes persist through [onBrandingChange], so a style set once here is the style every
 * later invoice is rendered with.
 */
@Composable
fun InvoiceEditorDialog(
    baseDoc: InvoiceDocument,
    branding: InvoiceBranding,
    voucherId: Long,
    onBrandingChange: (InvoiceBranding) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Edited locally so the preview tracks every keystroke; committed to the caller (and
    // so to DataStore) as it changes, which is what makes the styling stick for next time.
    var draft by remember(branding) { mutableStateOf(branding) }
    var busy by remember { mutableStateOf(false) }

    // Only a real edit is ever written back.
    //
    // Persisting `draft` unconditionally destroyed saved styling: the first composition
    // seeds draft from whatever the caller has, which at that moment can still be the
    // stream's placeholder default, and saving that overwrote the user's stored accent
    // with the factory one. Opening the invoice to look at it reset it.
    var edited by remember { mutableStateOf(false) }
    fun edit(block: (InvoiceBranding) -> InvoiceBranding) {
        draft = block(draft)
        edited = true
    }

    LaunchedEffect(draft, edited) { if (edited) onBrandingChange(draft) }

    val insets = dialogSystemBarInsets()

    val doc = remember(baseDoc, draft) { InvoiceAssembler.applyBranding(baseDoc, draft) }

    val logoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) { copyLogoToFiles(context, uri) }
            if (saved != null) {
                edit { it.copy(logo = InvoiceBranding.LogoChoice.CUSTOM, customLogoPath = saved) }
            } else {
                Toast.makeText(context, "Could not read that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The dialog window runs to the bottom of the screen, under the gesture bar, and
        // receives no insets of its own — so the action row is inset here instead.
        Surface(
            Modifier.fillMaxSize().padding(bottom = insets.fullScreenDialogBottom),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().testTag("invoice_editor")) {

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        doc.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                ) {
                    // The document is laid out at a fixed design width so its columns keep
                    // the proportions the PDF uses, then scaled down to whatever width the
                    // screen actually has — the whole page is visible at once, the way a
                    // PDF viewer shows one.
                    //
                    // It was a horizontal scroller first. At 360dp that put the entire
                    // totals column off-screen, so the invoice looked cut in half and you
                    // had to drag sideways to find your own total.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        ScaledToWidth(designWidth = PREVIEW_DESIGN_WIDTH) {
                            InvoicePreview(doc, draft, Modifier.width(PREVIEW_DESIGN_WIDTH))
                        }
                    }

                    SectionCard("APPEARANCE") {
                        Text("Accent colour", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            InvoiceBranding.PRESET_ACCENTS.forEach { (name, argb) ->
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .border(
                                            width = if (draft.accentArgb == argb) 3.dp else 1.dp,
                                            color = if (draft.accentArgb == argb) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                            shape = CircleShape
                                        )
                                        .clickable { edit { b -> b.copy(accentArgb = argb) } }
                                        .testTag("accent_$name"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (draft.accentArgb == argb) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = name,
                                            tint = OnAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Logo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        ChoiceChipRow {
                            ChoiceChip(
                                label = "iTaxEasy",
                                selected = draft.logo == InvoiceBranding.LogoChoice.ITAXEASY,
                                onClick = {
                                    edit { it.copy(logo = InvoiceBranding.LogoChoice.ITAXEASY) }
                                }
                            )
                            ChoiceChip(
                                label = "My logo",
                                selected = draft.logo == InvoiceBranding.LogoChoice.CUSTOM,
                                onClick = {
                                    logoPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            )
                            ChoiceChip(
                                label = "None",
                                selected = draft.logo == InvoiceBranding.LogoChoice.NONE,
                                onClick = {
                                    edit { it.copy(logo = InvoiceBranding.LogoChoice.NONE) }
                                }
                            )
                        }
                        if (draft.logo == InvoiceBranding.LogoChoice.CUSTOM) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    logoPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            ) { Text("Choose a different image", fontSize = 12.sp) }
                        }
                    }

                    SectionCard("DOCUMENT DETAILS") {
                        OutlinedTextField(
                            value = draft.companyNameOverride,
                            onValueChange = { v -> edit { it.copy(companyNameOverride = v) } },
                            label = { Text("Company name on invoice") },
                            placeholder = { Text(baseDoc.seller.name.ifBlank { "Your business name" }) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("field_company_name")
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = draft.titleOverride,
                            onValueChange = { v -> edit { it.copy(titleOverride = v) } },
                            label = { Text("Document heading") },
                            placeholder = { Text(baseDoc.title) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = draft.terms,
                            onValueChange = { v -> edit { it.copy(terms = v) } },
                            label = { Text("Terms & conditions") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = draft.footerNote,
                            onValueChange = { v -> edit { it.copy(footerNote = v) } },
                            label = { Text("Footer message") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Amounts, tax and the invoice number come from the posted voucher. " +
                                "Edit the voucher to change them, so the ledger changes with it.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { edit { InvoiceBranding() } }) {
                            Text("Reset to default style", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { InvoiceExporter.shareText(context, doc) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Text", maxLines = 1, softWrap = false) }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runExport(context, doc, draft, voucherId, print = true)
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Print", maxLines = 1, softWrap = false) }

                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                runExport(context, doc, draft, voucherId, print = false)
                                busy = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        modifier = Modifier.weight(1.4f).testTag("invoice_share_pdf")
                    ) { Text("Share PDF", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/**
 * The width the invoice preview is composed at before being scaled to the screen.
 *
 * Wide enough that the eight-column taxed table lays out with the same proportions the A4
 * PDF gives it; the scale step then fits it to the device.
 */
private val PREVIEW_DESIGN_WIDTH = 620.dp

/**
 * Lays [content] out at [designWidth] and draws it scaled to the available width.
 *
 * The scale is applied in `placeWithLayer` and the *reported* size is scaled too, so the
 * parent reserves exactly the space the shrunken page occupies. A plain `graphicsLayer`
 * would shrink the drawing while still measuring at full size, leaving a tall empty gap
 * under the invoice.
 */
@Composable
private fun ScaledToWidth(designWidth: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth
        val scale = (available / designWidth).coerceIn(0.1f, 1f)
        val designPx = with(LocalDensity.current) { designWidth.roundToPx() }

        Box(
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixedWidth(designPx))
                layout(
                    (placeable.width * scale).roundToInt(),
                    (placeable.height * scale).roundToInt()
                ) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        ) { content() }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RoyalPurplePrimary,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Renders off the main thread and then hands off.
 *
 * A multi-page PDF is drawn synchronously; doing that in the click handler janked the
 * frame the button was still animating in.
 */
private suspend fun runExport(
    context: Context,
    doc: InvoiceDocument,
    branding: InvoiceBranding,
    voucherId: Long,
    print: Boolean
) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val file = InvoiceExporter.renderPdf(context, doc, branding, voucherId)
            file to InvoicePdfRenderer.pageCount(context, doc, branding)
        }
    }
    result.onSuccess { (file, pages) ->
        if (print) {
            InvoiceExporter.printPdf(context, file, doc, pages)
        } else {
            InvoiceExporter.sharePdf(context, file, doc)
        }
    }.onFailure {
        Toast.makeText(context, "Could not create the PDF: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Copies the picked image into filesDir and returns its path.
 *
 * Copied rather than referenced: the picker grants read access to that Uri for this
 * process only, so a stored Uri would render today and fail after the next launch.
 */
private fun copyLogoToFiles(context: Context, uri: Uri): String? = runCatching {
    val target = InvoiceBrandingStore.logoFile(context)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output) }
    } ?: return null
    target.absolutePath
}.getOrNull()
