package com.example.data.gst

import com.google.gson.annotations.SerializedName

/**
 * Data structures for Government-Compliant GSTR-1 and GSTR-3B JSON exports.
 */

// --- GSTR-1 Models ---
data class Gstr1ItmDet(
    @SerializedName("rt") val rt: Double = 18.0,
    @SerializedName("txval") val txval: Double = 0.0,
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

data class Gstr1InvoiceItem(
    @SerializedName("num") val num: Int = 1,
    @SerializedName("itm_det") val itmDet: Gstr1ItmDet
)

data class Gstr1Invoice(
    @SerializedName("inum") val inum: String, // Truncated to max 16 chars
    @SerializedName("idt") val idt: String,   // dd-MM-yyyy format
    @SerializedName("val") val valAmt: Double,
    @SerializedName("pos") val pos: String,   // 2-digit state code
    @SerializedName("rchrg") val rchrg: String = "N",
    @SerializedName("inv_type") val invType: String = "R",
    @SerializedName("itms") val itms: List<Gstr1InvoiceItem>
)

data class Gstr1B2bGroup(
    @SerializedName("ctin") val ctin: String,
    @SerializedName("inv") val inv: List<Gstr1Invoice>
)

data class Gstr1B2csItem(
    @SerializedName("sply_ty") val splyTy: String, // INTRA or INTER
    @SerializedName("pos") val pos: String,
    @SerializedName("rt") val rt: Double,
    @SerializedName("txval") val txval: Double,
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

data class Gstr1HsnDetail(
    @SerializedName("num") val num: Int = 1,
    @SerializedName("hsn_sc") val hsnSc: String,
    @SerializedName("desc") val desc: String,
    @SerializedName("uqc") val uqc: String = "NOS",
    @SerializedName("qty") val qty: Double = 1.0,
    @SerializedName("val") val totalVal: Double = 0.0,
    @SerializedName("txval") val txval: Double = 0.0,
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

data class Gstr1HsnData(
    @SerializedName("data") val data: List<Gstr1HsnDetail>
)

data class Gstr1DocDetail(
    @SerializedName("doc_num") val docNum: Int = 1,
    @SerializedName("doc_typ") val docTyp: String = "Invoices for outward supply",
    @SerializedName("from") val from: String,
    @SerializedName("to") val to: String,
    @SerializedName("totcnt") val totcnt: Int,
    @SerializedName("cancel") val cancel: Int = 0,
    @SerializedName("net_issue") val netIssue: Int
)

data class Gstr1DocSummary(
    @SerializedName("doc_det") val docDet: List<Gstr1DocDetail>
)

/**
 * GSTR-1 Table 5A — inter-state supplies to unregistered persons above the threshold,
 * reported invoice-wise rather than in aggregate.
 *
 * There was no such model, so every B2CL invoice was folded into the aggregate B2CS
 * table in the payload actually filed — while the on-screen summary correctly showed it
 * as B2CL. The two surfaces disagreed about the same invoice.
 */
data class Gstr1B2clInvoice(
    @SerializedName("inum") val inum: String,
    @SerializedName("idt") val idt: String,
    @SerializedName("val") val valAmt: Double,
    @SerializedName("itms") val itms: List<Gstr1InvoiceItem>
)

data class Gstr1B2clGroup(
    @SerializedName("pos") val pos: String,
    @SerializedName("inv") val inv: List<Gstr1B2clInvoice>
)

/**
 * GSTR-1 Table 9B — a credit or debit note issued to a REGISTERED person (CDNR).
 *
 * No such model existed and [Gstr1Payload] had no `cdnr` key, so SALES_RETURN vouchers
 * were filtered out of the export entirely. Every ledger, the Trial Balance, the P&L and
 * the app's own GST summary net a credit note; only the filed return did not, so it
 * overstated output liability by the full value of every credit note issued.
 *
 * Values are POSITIVE and [ntty] carries the direction — the portal does the subtracting.
 * That happens to match how this app stores a SALES_RETURN: positive totalAmount and
 * gstAmount, with the reversal expressed by flipping which legs are debited.
 *
 * The original invoice's number and date are deliberately absent. They are not in the
 * current schema, and VoucherEntity carries no link from a return to the sale it
 * reverses, so they could only have been fabricated.
 */
data class Gstr1CreditNote(
    /** "C" credit note, "D" debit note. */
    @SerializedName("ntty") val ntty: String = "C",
    /** The NOTE's own number, not the original invoice's. */
    @SerializedName("nt_num") val ntNum: String,
    @SerializedName("nt_dt") val ntDt: String,
    @SerializedName("pos") val pos: String,
    @SerializedName("rchrg") val rchrg: String = "N",
    @SerializedName("inv_typ") val invTyp: String = "R",
    @SerializedName("val") val valAmt: Double,
    @SerializedName("itms") val itms: List<Gstr1InvoiceItem>
)

data class Gstr1CdnrGroup(
    @SerializedName("ctin") val ctin: String,
    @SerializedName("nt") val nt: List<Gstr1CreditNote>
)

/**
 * GSTR-1 Table 9B for an UNREGISTERED recipient (CDNUR). Flat: there is no counterparty
 * GSTIN to group by.
 *
 * [typ] admits only "B2CL", "EXPWP" and "EXPWOP". A credit note against a *small* B2C
 * supply has no CDNUR row at all — it nets into the Table 7 (B2CS) aggregate. Emitting
 * typ="B2CS" here would be rejected.
 */
data class Gstr1CdnurNote(
    @SerializedName("typ") val typ: String = "B2CL",
    @SerializedName("ntty") val ntty: String = "C",
    @SerializedName("nt_num") val ntNum: String,
    @SerializedName("nt_dt") val ntDt: String,
    @SerializedName("pos") val pos: String,
    @SerializedName("val") val valAmt: Double,
    @SerializedName("itms") val itms: List<Gstr1InvoiceItem>
)

data class Gstr1Payload(
    @SerializedName("gstin") val gstin: String,
    @SerializedName("fp") val fp: String, // MMYYYY
    @SerializedName("gt") val gt: Double = 0.0,
    @SerializedName("cur_gt") val curGt: Double = 0.0,
    @SerializedName("b2b") val b2b: List<Gstr1B2bGroup>,
    @SerializedName("b2cl") val b2cl: List<Gstr1B2clGroup> = emptyList(),
    @SerializedName("b2cs") val b2cs: List<Gstr1B2csItem>,
    @SerializedName("cdnr") val cdnr: List<Gstr1CdnrGroup> = emptyList(),
    @SerializedName("cdnur") val cdnur: List<Gstr1CdnurNote> = emptyList(),
    @SerializedName("hsn") val hsn: Gstr1HsnData,
    @SerializedName("doc_issue") val docIssue: Gstr1DocSummary
)

// --- GSTR-3B Models ---
data class Gstr3bTaxTuple(
    @SerializedName("txval") val txval: Double = 0.0,
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

data class Gstr3bSupDetails(
    @SerializedName("osup_det") val osupDet: Gstr3bTaxTuple,
    @SerializedName("osup_zero") val osupZero: Gstr3bTaxTuple = Gstr3bTaxTuple(),
    @SerializedName("osup_nil_exmp") val osupNilExmp: Gstr3bTaxTuple = Gstr3bTaxTuple(),
    @SerializedName("isup_rev") val isupRev: Gstr3bTaxTuple = Gstr3bTaxTuple(),
    @SerializedName("osup_nongst") val osupNongst: Gstr3bTaxTuple = Gstr3bTaxTuple()
)

data class Gstr3bItcItem(
    @SerializedName("ty") val // "OTH" per the GSTR-3B schema (IMPG/IMPS/ISRC/ISD/OTH). The old default was
    // not a value the portal accepts, and fixing only the call site left the rejection
    // one forgotten argument away.
    ty: String = "OTH", // Strict government structural type tag
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

/**
 * Table 4(C), net ITC = 4(A) minus 4(B).
 *
 * No `ty` member: the 3B schema tags the availment and reversal rows, never the net.
 * This was typed [Gstr3bItcItem], so `itc_net` emitted a stray `"ty":"OTH"` — and the
 * engine passed the SAME object as `itc_avl[0]`, which made net ITC structurally
 * incapable of differing from gross.
 *
 * Unlike Table 3.1 this is allowed to be NEGATIVE: reversal exceeding availment is a
 * real outcome that adds to the period's liability. Do not clamp it.
 */
data class Gstr3bItcNet(
    @SerializedName("iamt") val iamt: Double = 0.0,
    @SerializedName("camt") val camt: Double = 0.0,
    @SerializedName("samt") val samt: Double = 0.0,
    @SerializedName("csamt") val csamt: Double = 0.0
)

data class Gstr3bItcElg(
    @SerializedName("itc_avl") val itcAvl: List<Gstr3bItcItem>,
    /** Table 4(B). "RUL" = Rules 42/43; "OTH" = Others, where a purchase return goes. */
    @SerializedName("itc_rev") val itcRev: List<Gstr3bItcItem> = emptyList(),
    @SerializedName("itc_net") val itcNet: Gstr3bItcNet
)

data class Gstr3bPayload(
    @SerializedName("gstin") val gstin: String,
    @SerializedName("ret_period") val retPeriod: String, // MMYYYY
    @SerializedName("sup_details") val supDetails: Gstr3bSupDetails,
    @SerializedName("itc_elg") val itcElg: Gstr3bItcElg
)

// --- Master Export Wrapper ---
data class CombinedGstExportPayload(
    @SerializedName("export_timestamp") val exportTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("status") val status: String = "SUCCESS",
    @SerializedName("business_gstin") val businessGstin: String,
    @SerializedName("business_state_code") val businessStateCode: String,
    @SerializedName("period") val period: String, // MMYYYY
    @SerializedName("gstr1") val gstr1: Gstr1Payload,
    @SerializedName("gstr3b") val gstr3b: Gstr3bPayload
)
