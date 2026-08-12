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

data class Gstr1Payload(
    @SerializedName("gstin") val gstin: String,
    @SerializedName("fp") val fp: String, // MMYYYY
    @SerializedName("gt") val gt: Double = 0.0,
    @SerializedName("cur_gt") val curGt: Double = 0.0,
    @SerializedName("b2b") val b2b: List<Gstr1B2bGroup>,
    @SerializedName("b2cl") val b2cl: List<Gstr1B2clGroup> = emptyList(),
    @SerializedName("b2cs") val b2cs: List<Gstr1B2csItem>,
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

data class Gstr3bItcElg(
    @SerializedName("itc_avl") val itcAvl: List<Gstr3bItcItem>,
    @SerializedName("itc_rev") val itcRev: List<Gstr3bItcItem> = emptyList(),
    @SerializedName("itc_net") val itcNet: Gstr3bItcItem
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
