package com.example.utils

import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TallyMargParsedVoucher(
    val voucherNo: String,
    val date: String,
    val partyName: String,
    val voucherType: VoucherType,
    val amount: Double,
    val gstAmount: Double,
    val narration: String,
    val source: String // "TALLY_XML" or "MARG_XML"
)

enum class ConflictAction {
    SKIP,
    REPLACE,
    MERGE
}

data class ImportConflictItem(
    val importedVoucher: TallyMargParsedVoucher,
    val existingVoucher: VoucherEntity?,
    var chosenAction: ConflictAction = ConflictAction.SKIP
)

object TallyMargXmlUtil {

    private val tallyDateFormat = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH)
    private val standardDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    fun exportToTallyXml(vouchers: List<VoucherEntity>, user: UserEntity): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ENVELOPE>\n")
        sb.append("  <HEADER>\n")
        sb.append("    <TALLYREQUEST>Import Data</TALLYREQUEST>\n")
        sb.append("    <COMPANY>${user.businessName.replace("&", "&amp;")}</COMPANY>\n")
        sb.append("    <GSTIN>${user.gstin}</GSTIN>\n")
        sb.append("  </HEADER>\n")
        sb.append("  <BODY>\n")
        sb.append("    <IMPORTDATA>\n")
        sb.append("      <REQUESTDESC>\n")
        sb.append("        <REPORTNAME>Vouchers</REPORTNAME>\n")
        sb.append("      </REQUESTDESC>\n")
        sb.append("      <REQUESTDATA>\n")

        vouchers.forEach { v ->
            val dateStr = tallyDateFormat.format(Date(v.date))
            sb.append("        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n")
            sb.append("          <VOUCHER VCHTYPE=\"${v.voucherType.name}\" ACTION=\"Create\">\n")
            sb.append("            <DATE>$dateStr</DATE>\n")
            sb.append("            <VOUCHERTYPENAME>${v.voucherType.name}</VOUCHERTYPENAME>\n")
            sb.append("            <VOUCHERNUMBER>${v.voucherNo}</VOUCHERNUMBER>\n")
            sb.append("            <PARTYLEDGERNAME>${v.partyName.replace("&", "&amp;")}</PARTYLEDGERNAME>\n")
            sb.append("            <NARRATION>${v.narration.replace("&", "&amp;")}</NARRATION>\n")
            sb.append("            <AMOUNT>${v.totalAmount}</AMOUNT>\n")
            sb.append("            <GSTAMOUNT>${v.gstAmount}</GSTAMOUNT>\n")
            sb.append("            <ISINTERSTATE>${if (v.isInterstate) "YES" else "NO"}</ISINTERSTATE>\n")
            sb.append("          </VOUCHER>\n")
            sb.append("        </TALLYMESSAGE>\n")
        }

        sb.append("      </REQUESTDATA>\n")
        sb.append("    </IMPORTDATA>\n")
        sb.append("  </BODY>\n")
        sb.append("</ENVELOPE>")
        return sb.toString()
    }

    fun exportToMargXml(vouchers: List<VoucherEntity>, user: UserEntity): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MARG_DATA_EXPORT>\n")
        sb.append("  <COMPANY_INFO>\n")
        sb.append("    <COMPANY_NAME>${user.businessName.replace("&", "&amp;")}</COMPANY_NAME>\n")
        sb.append("    <GSTIN>${user.gstin}</GSTIN>\n")
        sb.append("  </COMPANY_INFO>\n")
        sb.append("  <TRANSACTIONS>\n")

        vouchers.forEach { v ->
            val dateStr = standardDateFormat.format(Date(v.date))
            sb.append("    <ENTRY>\n")
            sb.append("      <VOUCHER_NO>${v.voucherNo}</VOUCHER_NO>\n")
            sb.append("      <TYPE>${v.voucherType.name}</TYPE>\n")
            sb.append("      <DATE>$dateStr</DATE>\n")
            sb.append("      <PARTY_NAME>${v.partyName.replace("&", "&amp;")}</PARTY_NAME>\n")
            sb.append("      <NET_AMOUNT>${v.totalAmount}</NET_AMOUNT>\n")
            sb.append("      <TAX_AMOUNT>${v.gstAmount}</TAX_AMOUNT>\n")
            sb.append("      <REMARKS>${v.narration.replace("&", "&amp;")}</REMARKS>\n")
            sb.append("    </ENTRY>\n")
        }

        sb.append("  </TRANSACTIONS>\n")
        sb.append("</MARG_DATA_EXPORT>")
        return sb.toString()
    }

    fun parseTallyOrMargXml(xmlContent: String): List<TallyMargParsedVoucher> {
        val parsedList = mutableListOf<TallyMargParsedVoucher>()
        val isTally = xmlContent.contains("TALLY", ignoreCase = true) || xmlContent.contains("<VOUCHER", ignoreCase = true)
        val sourceTag = if (isTally) "TALLY_XML" else "MARG_XML"

        // Simple robust regex extraction for Tally / Marg tags
        val voucherBlocks = if (isTally) {
            xmlContent.split(Regex("(?i)<VOUCHER|(?i)<TALLYMESSAGE"))
        } else {
            xmlContent.split(Regex("(?i)<ENTRY"))
        }

        voucherBlocks.forEachIndexed { index, block ->
            if (index == 0) return@forEachIndexed // skip pre-header

            val partyName = extractXmlTag(block, "PARTYLEDGERNAME").ifBlank { extractXmlTag(block, "PARTY_NAME") }
            val date = extractXmlTag(block, "DATE")
            val amountStr = extractXmlTag(block, "AMOUNT").ifBlank { extractXmlTag(block, "NET_AMOUNT") }
            val gstAmountStr = extractXmlTag(block, "GSTAMOUNT").ifBlank { extractXmlTag(block, "TAX_AMOUNT") }
            val vTypeStr = extractXmlTag(block, "VOUCHERTYPENAME").ifBlank { extractXmlTag(block, "VCHTYPE") }.ifBlank { extractXmlTag(block, "TYPE") }
            val narration = extractXmlTag(block, "NARRATION").ifBlank { extractXmlTag(block, "REMARKS") }
            val vNo = extractXmlTag(block, "VOUCHERNUMBER").ifBlank { extractXmlTag(block, "VOUCHER_NO") }.ifBlank { "IMP-${System.currentTimeMillis() % 10000}-$index" }

            val amount = amountStr.toDoubleOrNull() ?: 0.0
            val gstAmount = gstAmountStr.toDoubleOrNull() ?: 0.0

            if (partyName.isNotBlank() && amount > 0.0) {
                val vType = when {
                    vTypeStr.contains("SALE", ignoreCase = true) -> VoucherType.SALES
                    vTypeStr.contains("PURCHASE", ignoreCase = true) -> VoucherType.PURCHASE
                    vTypeStr.contains("PAYMENT", ignoreCase = true) -> VoucherType.PAYMENT
                    vTypeStr.contains("RECEIPT", ignoreCase = true) -> VoucherType.RECEIPT
                    vTypeStr.contains("CONTRA", ignoreCase = true) -> VoucherType.CONTRA
                    vTypeStr.contains("JOURNAL", ignoreCase = true) -> VoucherType.JOURNAL
                    else -> VoucherType.JOURNAL
                }

                parsedList.add(
                    TallyMargParsedVoucher(
                        voucherNo = vNo,
                        date = if (date.length == 8) "${date.substring(0,4)}-${date.substring(4,6)}-${date.substring(6,8)}" else date,
                        partyName = partyName.replace("&amp;", "&"),
                        voucherType = vType,
                        amount = amount,
                        gstAmount = gstAmount,
                        narration = narration.replace("&amp;", "&"),
                        source = sourceTag
                    )
                )
            }
        }

        return parsedList
    }

    private fun extractXmlTag(content: String, tagName: String): String {
        val pattern = Regex("(?i)<$tagName[^>]*>(.*?)</$tagName>")
        val match = pattern.find(content)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }
}
