package com.example.invoice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.BitmapFactory
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.InkBlack
import com.example.ui.theme.InvoiceInkMuted
import com.example.ui.theme.InvoiceInkStrong
import com.example.ui.theme.InvoiceRowStripe
import com.example.ui.theme.InvoiceRule
import com.example.ui.theme.OnAccent
import com.example.ui.theme.PaperSurface
import com.example.utils.IndianFormatter

/**
 * The on-screen rendering of an [InvoiceDocument].
 *
 * Reads the same document, the same [InvoiceColumn] set and the same branding as
 * [InvoicePdfRenderer], so what the user approves here is what the PDF contains. The
 * previous preview was an independent layout and had already drifted from the PDF in both
 * directions — it showed Terms and a UPI block the PDF omitted, and omitted the signature
 * and the document-type heading the PDF drew.
 *
 * Stays light regardless of app theme: it is a picture of a sheet of paper.
 */
@Composable
fun InvoicePreview(
    doc: InvoiceDocument,
    branding: InvoiceBranding,
    modifier: Modifier = Modifier
) {
    val accent = Color(branding.accentArgb)
    val columns = InvoiceColumn.forDocument(doc)
    val weights = InvoiceColumn.normalisedWeights(columns)

    Column(
        modifier
            .fillMaxWidth()
            .background(PaperSurface)
    ) {
        Banner(doc, branding, accent)

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            MetaBlock(doc, accent)
            Spacer(Modifier.height(14.dp))
            ItemTable(doc, columns, weights, accent)
            Spacer(Modifier.height(14.dp))
            Summary(doc, accent)
            Spacer(Modifier.height(18.dp))
        }

        Footer(doc, accent)
    }
}

@Composable
private fun Banner(doc: InvoiceDocument, branding: InvoiceBranding, accent: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(accent)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Logo(branding)
                Spacer(Modifier.height(8.dp))
                Text(
                    doc.title,
                    color = OnAccent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                if (doc.seller.name.isNotBlank()) {
                    Text(
                        doc.seller.name,
                        color = OnAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                }
                doc.seller.detailLines().take(5).forEach {
                    Text(
                        it,
                        color = OnAccent.copy(alpha = 0.88f),
                        fontSize = 8.5.sp,
                        // Explicit, because the default line height for a small sp size
                        // spaces a letterhead block much further apart than the PDF's.
                        lineHeight = 11.sp,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The mark, on a white plate for the same reason the PDF uses one. */
@Composable
private fun Logo(branding: InvoiceBranding) {
    when (branding.effectiveLogo()) {
        InvoiceBranding.LogoChoice.NONE -> Unit

        InvoiceBranding.LogoChoice.ITAXEASY -> Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(PaperSurface)
                .padding(5.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_itaxeasy_logo),
                contentDescription = "iTaxEasy",
                modifier = Modifier.size(width = 84.dp, height = 36.dp),
                contentScale = ContentScale.Fit
            )
        }

        InvoiceBranding.LogoChoice.CUSTOM -> {
            val file = branding.resolvedCustomLogo()
            // Decoded directly rather than through an image loader: Coil is deliberately
            // excluded from this build, and the target is one small local file whose path
            // is the only thing that changes.
            val bitmap = remember(file?.absolutePath, file?.lastModified()) {
                file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            }
            if (bitmap != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(PaperSurface)
                        .padding(5.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Company logo",
                        modifier = Modifier.heightIn(max = 36.dp).width(96.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaBlock(doc: InvoiceDocument, accent: Color) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            MetaField(doc.docNoLabel, doc.docNo.ifBlank { "—" })
            MetaField("Date of Issue", IndianFormatter.formatDate(doc.dateMillis))
            if (doc.placeOfSupply.isNotBlank()) MetaField("Place of Supply", doc.placeOfSupply)
            if (doc.paymentMode.isNotBlank()) MetaField("Payment Mode", doc.paymentMode)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                doc.buyerLabel,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                doc.buyer.name.ifBlank { "—" },
                color = InvoiceInkStrong,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
            doc.buyer.detailLines().take(4).forEach {
                Text(
                    it,
                    color = InvoiceInkMuted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun MetaField(label: String, value: String) {
    Row(Modifier.padding(bottom = 3.dp)) {
        Text(
            label,
            color = InvoiceInkMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(86.dp)
        )
        Text(value, color = InvoiceInkStrong, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ItemTable(
    doc: InvoiceDocument,
    columns: List<InvoiceColumn>,
    weights: List<Float>,
    accent: Color
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.4.dp).background(accent))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            columns.forEachIndexed { i, col ->
                Text(
                    col.header,
                    color = accent,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = if (col.alignEnd) TextAlign.End else TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.weight(weights[i]).padding(horizontal = 2.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.4.dp).background(accent))

        doc.lines.forEachIndexed { index, line ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 1) InvoiceRowStripe else PaperSurface)
                    .padding(vertical = 7.dp)
            ) {
                columns.forEachIndexed { i, col ->
                    // Only the description may wrap. A money or quantity cell that wraps
                    // breaks the number itself across two lines — "₹3,933." above "33" —
                    // which reads as a different amount. Those ellipsise instead, matching
                    // the PDF, where every non-description cell goes through fit().
                    val wraps = col == InvoiceColumn.DESCRIPTION
                    Text(
                        col.cell(line, index),
                        color = InvoiceInkStrong,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (col == InvoiceColumn.AMOUNT) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (col.alignEnd) TextAlign.End else TextAlign.Start,
                        maxLines = if (wraps) 3 else 1,
                        softWrap = wraps,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(weights[i]).padding(horizontal = 2.dp)
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(0.6.dp).background(InvoiceRule))
        }
    }
}

@Composable
private fun Summary(doc: InvoiceDocument, accent: Color) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            if (doc.amountInWords.isNotBlank()) {
                SummaryNote("Amount in Words", doc.amountInWords)
            }
            if (doc.notes.isNotBlank()) SummaryNote("Notes", doc.notes)
            if (doc.terms.isNotBlank()) SummaryNote("Terms", doc.terms)
            if (doc.upiId.isNotBlank()) {
                Text(
                    "Pay via UPI: ${doc.upiId}",
                    color = InvoiceInkStrong,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(Modifier.weight(1f)) {
            TotalRow("Subtotal", doc.subtotal)
            doc.taxRows.forEach { TotalRow(it.label, it.amount) }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(accent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total", color = OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    IndianFormatter.formatRupee(doc.total),
                    color = OnAccent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(20.dp))
            if (doc.signatoryLine.isNotBlank()) {
                Text(
                    doc.signatoryLine,
                    color = InvoiceInkStrong,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Mirrors the PDF, which stamps the saved signature above the rule.
            val context = LocalContext.current
            val signature = remember {
                InvoiceBrandingStore.signatureFile(context)?.let {
                    runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
                }
            }
            if (signature != null) {
                Image(
                    bitmap = signature.asImageBitmap(),
                    contentDescription = "Authorised signature",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .padding(start = 60.dp)
                )
            } else {
                Spacer(Modifier.height(22.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.8.dp).background(InvoiceRule))
            Text(
                "Authorised Signatory",
                color = InvoiceInkMuted,
                fontSize = 8.5.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SummaryNote(label: String, body: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(label, color = InvoiceInkStrong, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(body, color = InvoiceInkMuted, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun TotalRow(label: String, amount: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = InvoiceInkMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        Text(IndianFormatter.formatRupee(amount), color = InkBlack, fontSize = 9.5.sp)
    }
}

@Composable
private fun Footer(doc: InvoiceDocument, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(accent)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (doc.footerNote.isNotBlank()) {
            Text(
                doc.footerNote,
                color = OnAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
