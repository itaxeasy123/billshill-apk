package com.example.data.ocr

import android.content.Context
import android.net.Uri
import com.example.BuildConfig
import com.example.data.model.VoucherType
import com.example.utils.GstCalculationService
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

// --- wire format -------------------------------------------------------------------
//
// Mirrors POST /api/billshield-invoice. Every monetary field is nullable: the parser
// returns nulls rather than raising when a field is absent, because a missing figure is
// missing data, not a failure — and the caller has to be able to tell the difference.

@JsonClass(generateAdapter = true)
data class OcrInvoiceResponse(val message: String?, val data: OcrInvoiceData?)

@JsonClass(generateAdapter = true)
data class OcrInvoiceData(
    val party_name: String?,
    val invoice: OcrInvoiceRef?,
    val totals: OcrInvoiceTotals?,
    val gst_rate: Double?,
    val is_interstate: Boolean?,
    val voucher_type: String?,
    val notes: String?,
    val meta: OcrInvoiceMeta?
)

@JsonClass(generateAdapter = true)
data class OcrInvoiceRef(val number: String?, val date: String?)

@JsonClass(generateAdapter = true)
data class OcrInvoiceTotals(
    val taxable_value: Double?,
    val gst_amount: Double?,
    val grand_total: Double?,
    val cgst: Double?,
    val sgst: Double?,
    val igst: Double?
)

@JsonClass(generateAdapter = true)
data class OcrInvoiceMeta(val source: String?, val format: String?, val reconciles: Boolean?)

interface OcrApi {
    @Multipart
    @POST("billshield-invoice")
    suspend fun parseInvoice(@Part file: MultipartBody.Part): Response<OcrInvoiceResponse>
}

/** What the caller gets back: either usable figures, or a reason it cannot be used. */
sealed interface InvoiceImportResult {
    data class Extracted(
        val partyName: String,
        val amountInclusive: Double,
        val gstRate: Double,
        val isInterstate: Boolean,
        val voucherType: VoucherType,
        val invoiceNo: String?,
        val narration: String
    ) : InvoiceImportResult

    data class Failed(val reason: String) : InvoiceImportResult
}

/**
 * Reads an invoice PDF by uploading it to the OCR service's BillShield endpoint.
 *
 * The endpoint extracts the PDF's embedded text layer rather than OCR-ing a rasterised
 * image, so for invoices this app generated the figures come back exact. That distinction
 * is the whole reason this is worth doing: a misread digit on an accounting document posts
 * a wrong amount that balances perfectly and is invisible afterwards.
 *
 * Nothing here posts a voucher. The result fills the entry form and the user confirms it —
 * an extracted figure is a suggestion, not an authority.
 */
object OcrInvoiceClient {

    private val api: OcrApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            // Generous: the service cold-starts torch and YOLO for its other endpoints,
            // and a first request after idle can take a while even though this path
            // touches neither.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            // From OCR_API_BASE_URL in the gitignored .env, injected at build time.
            .baseUrl(BuildConfig.OCR_API_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(OcrApi::class.java)
    }

    suspend fun importInvoice(context: Context, uri: Uri): InvoiceImportResult =
        withContext(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            } ?: return@withContext InvoiceImportResult.Failed("Could not read that file.")

            val part = MultipartBody.Part.createFormData(
                "file", "invoice.pdf",
                bytes.toRequestBody("application/pdf".toMediaTypeOrNull())
            )

            val body = try {
                val resp = api.parseInvoice(part)
                if (!resp.isSuccessful) {
                    return@withContext InvoiceImportResult.Failed(
                        "The invoice reader returned an error (${resp.code()})."
                    )
                }
                resp.body()?.data
            } catch (e: Exception) {
                return@withContext InvoiceImportResult.Failed(
                    "Could not reach the invoice reader. Check your connection."
                )
            } ?: return@withContext InvoiceImportResult.Failed("Nothing could be read from that file.")

            toResult(body)
        }

    /**
     * Maps a response onto the entry form, refusing anything the books would reject.
     *
     * The guards are not defensive padding. A partial extraction that posts is worse than
     * one that stops and says why: the voucher balances either way, so nothing downstream
     * would ever catch it.
     */
    internal fun toResult(d: OcrInvoiceData): InvoiceImportResult {
        val party = d.party_name?.trim().orEmpty()
        if (party.isBlank()) {
            return InvoiceImportResult.Failed("No party name found on that invoice.")
        }

        val amount = d.totals?.grand_total
        if (amount == null || amount <= 0.0) {
            return InvoiceImportResult.Failed("No invoice total found on that invoice.")
        }

        // taxable + gst == total, and the heads sum to the gst. The server reports this;
        // a parse that does not reconcile has misread something.
        if (d.meta?.reconciles != true) {
            return InvoiceImportResult.Failed(
                "The figures on that invoice do not add up — enter it manually."
            )
        }

        // addVoucher compares the rate against the slab list by exact equality, so an
        // unsnapped 18.0637 would be refused at save time with a confusing message. Better
        // to say so here, where the cause is still visible.
        val rate = d.gst_rate
        if (rate == null || !rate.isFinite() || rate !in GstCalculationService.SLABS) {
            return InvoiceImportResult.Failed(
                "The tax on that invoice works out to a rate that is not a GST slab."
            )
        }

        // Only SALES and PURCHASE post tax legs, and only those two are offered by the
        // form. The generator prints "ACCOUNTING VOUCHER" for returns, journals and
        // contras, so those come back unrecognised rather than mislabelled.
        val type = when (d.voucher_type) {
            "SALES" -> VoucherType.SALES
            "PURCHASE" -> VoucherType.PURCHASE
            else -> return InvoiceImportResult.Failed(
                "That document is not a sales or purchase invoice."
            )
        }

        return InvoiceImportResult.Extracted(
            partyName = party,
            // The grand total is GST-INCLUSIVE. Every consumer must treat it as such —
            // the voucher wizard defaults to Exclusive and would otherwise gross it up a
            // second time.
            amountInclusive = amount,
            gstRate = rate,
            isInterstate = d.is_interstate == true,
            voucherType = type,
            invoiceNo = d.invoice?.number?.trim()?.takeIf { it.isNotBlank() },
            narration = d.notes?.trim().orEmpty()
        )
    }
}
