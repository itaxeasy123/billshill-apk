package com.example.data.gst

import com.example.data.model.LedgerEntity
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.utils.GstCalculationService

/**
 * How much a finding actually costs, which is the only ordering a user can act on.
 *
 * Deliberately not a score. There is no defensible way to weigh a missing narration
 * against charging the wrong tax head, and the percentage that used to sit on the Audit
 * Trail tab invented one — then painted a green shield above it.
 */
enum class AuditSeverity(val heading: String, val blurb: String) {
    BLOCKS_EXPORT(
        "Will stop your GST export",
        "The GSTR-1 / GSTR-3B export aborts on these. Fix them before filing."
    ),
    WRONG_RETURN(
        "Would make your return wrong",
        "These export successfully but carry figures that contradict the invoice."
    ),
    INCOMPLETE_RECORD(
        "Incomplete records",
        "Not a filing blocker. Required for the books under s.35(1) CGST Act and Rule 56."
    )
}

/** One problem with one voucher. A voucher with three problems yields three of these. */
data class AuditFinding(
    val voucherId: Long,
    val voucherNo: String,
    val partyName: String,
    val amount: Double,
    val severity: AuditSeverity,
    val detail: String
)

/**
 * The result of checking one period.
 *
 * [affectedVouchers] is DISTINCT by voucher: one voucher that is both zero-value and
 * un-narrated is ONE affected voucher, not two. The block this replaces summed four
 * overlapping filter sizes as raw counts and hid the double-count behind `coerceAtMost`,
 * so a ten-voucher book could report "13 Issues" beside "10 Entries" on the same row.
 */
data class AuditResult(
    val vouchersChecked: Int,
    val findings: List<AuditFinding>,
    /** Business-profile problems. One each, however many vouchers exist. */
    val settingsIssues: List<String>
) {
    val affectedVouchers: Int get() = findings.distinctBy { it.voucherId }.size
    val hasNothingToCheck: Boolean get() = vouchersChecked == 0
    val isClean: Boolean get() = !hasNothingToCheck && findings.isEmpty() && settingsIssues.isEmpty()
}

/**
 * Checks that are actually about GST, over the vouchers actually in the selected period.
 *
 * What this deliberately does NOT do is assert compliance. It cannot see filed returns,
 * e-invoice IRNs, e-way bills, reverse charge under s.9(3), ITC reversal under Rule 37,
 * or blocked credits under s.17(5). The block it replaces printed "N% COMPLIANT" over a
 * green shield on the strength of a narration-and-party fill rate — asserting all of
 * that from none of it, and scoring an empty book 100. It is the same claim
 * `UserEntity.gstStatus` was left blank to avoid making.
 *
 * Pure Kotlin with no Android or Compose dependency, so it can be tested on the JVM —
 * the same reason `FinancialStatementEngine` is shaped this way, and the only shape this
 * repo has test infrastructure for.
 */
object GstAuditEngine {

    fun audit(
        vouchers: List<VoucherEntity>,
        ledgersByName: Map<String, LedgerEntity>,
        user: UserEntity
    ): AuditResult {
        val sellerCode = GstStateCodes.fromGstin(user.gstin) ?: GstStateCodes.codeFor(user.state)
        val findings = mutableListOf<AuditFinding>()

        vouchers.forEach { v ->
            val party = ledgersByName[v.partyName.trim().lowercase()]
            val partyGstin = party?.gstin?.trim().orEmpty()
            // Credit notes reach GSTR-1 Table 9B and are subject to the same place-of-supply
            // resolution as a sale — so if this only inspected SALES, a credit note with an
            // unrecognised party state would pass the audit clean and then abort the whole
            // export, on data this screen had just certified.
            val isSale = v.voucherType == VoucherType.SALES ||
                v.voucherType == VoucherType.SALES_RETURN

            fun flag(severity: AuditSeverity, detail: String) {
                findings += AuditFinding(
                    v.id, v.voucherNo, v.partyName, v.totalAmount, severity, detail
                )
            }

            // GST checks apply to outward documents: a CONTRA or a JOURNAL never reaches
            // GSTR-1.
            if (isSale) {
                // A GSTIN that is not 15 characters is not a GSTIN. GstClassifier reads it
                // as unregistered, so the invoice silently drops out of Table 4A into the
                // B2C aggregate and the customer cannot claim their credit.
                if (partyGstin.isNotEmpty() && !GstClassifier.isRegistered(partyGstin)) {
                    flag(
                        AuditSeverity.WRONG_RETURN,
                        "${v.partyName}'s GSTIN is ${partyGstin.length} characters, not 15. " +
                            "This invoice will be filed as B2C instead of B2B."
                    )
                }

                val buyerCode = GstStateCodes.fromGstin(partyGstin)
                    ?: party?.state?.takeIf { it.isNotBlank() }?.let { GstStateCodes.codeFor(it) }

                // The export throws on an unrecognised state name, and silently substitutes
                // the seller's own state when it is blank — which is wrong for an
                // interstate supply. Both are knowable before the user presses export.
                if (buyerCode == null) {
                    val stateNote = party?.state.orEmpty()
                    flag(
                        AuditSeverity.BLOCKS_EXPORT,
                        if (stateNote.isBlank())
                            "No place of supply: ${v.partyName} has neither a GSTIN nor a state."
                        else
                            "'$stateNote' on ${v.partyName} is not a recognised Indian state."
                    )
                }

                // The tax head must match the place of supply. The export reports whatever
                // was posted, on purpose, so nothing else in the app catches this — and
                // charging the wrong head means the buyer cannot claim the credit and the
                // seller pays the right government afterwards, under s.77.
                if (buyerCode != null && sellerCode != null && v.gstAmount > 0.0) {
                    val shouldBeInterstate = buyerCode != sellerCode
                    if (shouldBeInterstate != v.isInterstate) {
                        flag(
                            AuditSeverity.WRONG_RETURN,
                            if (shouldBeInterstate)
                                "Charged CGST+SGST, but ${v.partyName} is in state $buyerCode " +
                                    "and you are in $sellerCode. This is an inter-state " +
                                    "supply and needs IGST."
                            else
                                "Charged IGST, but ${v.partyName} is in your own state " +
                                    "($sellerCode). This needs CGST+SGST."
                        )
                    }
                }
            }

            // The tax must correspond to a real slab. deriveGstRate already returns
            // off-slab rates unsnapped precisely so they stay visible; nothing consumed
            // that until now.
            if (v.gstAmount > 0.0) {
                val rate = GstCalculationService.deriveGstRate(v.totalAmount, v.gstAmount)
                if (!GstCalculationService.isLegalSlab(rate)) {
                    flag(
                        AuditSeverity.WRONG_RETURN,
                        "Tax works out to ${"%.2f".format(rate)}%, which is not a GST slab. " +
                            "The rate filed will contradict the tax printed on the invoice."
                    )
                }
            }

            // Record completeness. Real, but not GST, and labelled as such.
            if (v.totalAmount <= 0.0) flag(AuditSeverity.INCOMPLETE_RECORD, "Voucher value is zero or negative.")
            if (v.partyName.isBlank()) flag(AuditSeverity.INCOMPLETE_RECORD, "No party recorded.")
            if (v.narration.isBlank()) flag(AuditSeverity.INCOMPLETE_RECORD, "No narration.")
        }

        // Business profile: ONE fact each, however many vouchers exist. A blank GSTIN used
        // to be tested inside a per-voucher filter whose predicate did not vary by row, so
        // one empty field in Settings became N anomalies and drove 30 of 35 invoices
        // "non-compliant".
        val settingsIssues = buildList {
            when {
                user.gstin.isBlank() ->
                    add("Your GSTIN is not set. Every GST export will abort until you add it in Settings.")
                !GstClassifier.isRegistered(user.gstin) ->
                    add("Your GSTIN is ${user.gstin.trim().length} characters, not 15. Check it in Settings.")
            }
            if (sellerCode == null) {
                add(
                    "Your state cannot be determined from your GSTIN or your saved state, " +
                        "so no export can decide between CGST+SGST and IGST."
                )
            }
            if (user.businessName.isBlank()) {
                add("Your business name is blank; it will be missing from invoices and returns.")
            }
        }

        return AuditResult(vouchers.size, findings, settingsIssues)
    }
}
