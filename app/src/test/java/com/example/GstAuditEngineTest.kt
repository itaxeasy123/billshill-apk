package com.example

import com.example.data.gst.AuditSeverity
import com.example.data.gst.GstAuditEngine
import com.example.data.model.LedgerCategory
import com.example.data.model.LedgerEntity
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Audit Trail tab used to print "N% COMPLIANT" over a green shield. The number was
 * `(vouchers - anomalies) / vouchers`, where `anomalies` summed four OVERLAPPING filters
 * as raw sizes; where one blank GSTIN field in Settings was multiplied by the voucher
 * count; where an empty book scored 100; and where nothing in the formula touched GST.
 *
 * Each test below pins one of those four defects, plus the checks that replaced them.
 */
class GstAuditEngineTest {

    private val seller = UserEntity(
        id = "primary_user",
        phoneNumber = "9876543210",
        token = "",
        gstin = "07AABCU9603R1ZM",   // Delhi (07)
        businessName = "Test Traders",
        state = "Delhi"
    )

    private fun ledger(name: String, gstin: String = "", state: String = "") = LedgerEntity(
        id = name.hashCode().toLong(),
        name = name,
        groupId = 1L,
        groupName = "Sundry Debtors",
        category = LedgerCategory.ASSET,
        gstin = gstin,
        state = state
    )

    private fun sale(
        id: Long = 1L,
        party: String = "Mehta Traders",
        amount: Double = 11_800.0,
        gst: Double = 1_800.0,
        interstate: Boolean = false,
        narration: String = "sale",
        type: VoucherType = VoucherType.SALES
    ) = VoucherEntity(
        id = id,
        voucherNo = "INV/2026-27/100$id",
        voucherType = type,
        date = 0L,
        partyName = party,
        totalAmount = amount,
        gstAmount = gst,
        isInterstate = interstate,
        narration = narration
    )

    private fun ledgersOf(vararg l: LedgerEntity) = l.associateBy { it.name.trim().lowercase() }

    // ---- defect (a): an empty book scored 100 and drew a green shield -----------------

    @Test
    fun `an empty period is not a clean period`() {
        val r = GstAuditEngine.audit(emptyList(), emptyMap(), seller)

        assertTrue("must report that there is nothing to check", r.hasNothingToCheck)
        assertTrue(r.findings.isEmpty())
        assertFalse(
            "an empty book must never render as clean — that was the green shield",
            r.isClean
        )
    }

    // ---- defect (b): overlapping sets summed as sizes ---------------------------------

    @Test
    fun `one voucher with three problems is one affected voucher`() {
        val v = sale(amount = 0.0, gst = 0.0, party = "", narration = "")
        val r = GstAuditEngine.audit(listOf(v), emptyMap(), seller)

        // Four: zero value, no party, no narration — and, because a blank party on a SALES
        // voucher also resolves to no ledger, no place of supply either.
        assertEquals("four distinct problems", 4, r.findings.size)
        assertEquals(
            "but ONE affected voucher — the old block summed overlapping filter sizes " +
                "and reported more issues than it had vouchers",
            1, r.affectedVouchers
        )
        assertTrue(
            "never more affected vouchers than vouchers checked",
            r.affectedVouchers <= r.vouchersChecked
        )
    }

    // ---- defect (c): one Settings field multiplied by the voucher count ---------------

    @Test
    fun `a blank seller GSTIN is one settings issue, not one per voucher`() {
        val noGstin = seller.copy(gstin = "")
        val vouchers = (1L..30L).map { sale(id = it, amount = 60_000.0) }
        val r = GstAuditEngine.audit(vouchers, ledgersOf(ledger("Mehta Traders", state = "Delhi")), noGstin)

        assertEquals("exactly one settings issue for the blank GSTIN", 1, r.settingsIssues.size)
        assertTrue(r.settingsIssues.single().contains("GSTIN"))
        assertEquals(
            "and it must not be attributed to any voucher",
            0, r.findings.count { it.detail.contains("Your GSTIN") }
        )
    }

    // ---- defect (d): the formula touched no GST rule at all --------------------------

    @Test
    fun `an interstate buyer charged CGST and SGST is flagged`() {
        // Seller in Delhi (07), buyer in Maharashtra (27), posted intrastate.
        val buyer = ledger("Mehta Traders", gstin = "27AABCU9603R1ZM")
        val r = GstAuditEngine.audit(listOf(sale(interstate = false)), ledgersOf(buyer), seller)

        val f = r.findings.single { it.severity == AuditSeverity.WRONG_RETURN }
        assertTrue("must name the correct head: ${f.detail}", f.detail.contains("IGST"))
    }

    @Test
    fun `an intrastate buyer charged IGST is flagged`() {
        val buyer = ledger("Mehta Traders", gstin = "07AABCU9603R1ZM")
        val r = GstAuditEngine.audit(listOf(sale(interstate = true)), ledgersOf(buyer), seller)

        val f = r.findings.single { it.severity == AuditSeverity.WRONG_RETURN }
        assertTrue("must name the correct head: ${f.detail}", f.detail.contains("CGST+SGST"))
    }

    @Test
    fun `a correctly-headed interstate sale is not flagged`() {
        val buyer = ledger("Mehta Traders", gstin = "27AABCU9603R1ZM")
        val r = GstAuditEngine.audit(listOf(sale(interstate = true)), ledgersOf(buyer), seller)

        assertTrue("no tax-head complaint", r.findings.none { it.detail.contains("needs") })
    }

    @Test
    fun `a party with no GSTIN and no state blocks the export`() {
        // The export throws on this; the user should learn before pressing the button.
        val buyer = ledger("Mehta Traders")
        val r = GstAuditEngine.audit(listOf(sale()), ledgersOf(buyer), seller)

        assertTrue(r.findings.any { it.severity == AuditSeverity.BLOCKS_EXPORT })
    }

    @Test
    fun `an unrecognised state blocks the export and names the value`() {
        val buyer = ledger("Mehta Traders", state = "Bombay")
        val r = GstAuditEngine.audit(listOf(sale()), ledgersOf(buyer), seller)

        val f = r.findings.single { it.severity == AuditSeverity.BLOCKS_EXPORT }
        assertTrue("must quote the offending value: ${f.detail}", f.detail.contains("Bombay"))
    }

    @Test
    fun `a short GSTIN is flagged as silently downgrading B2B to B2C`() {
        val buyer = ledger("Mehta Traders", gstin = "27AABCU96", state = "Maharashtra")
        val r = GstAuditEngine.audit(listOf(sale(interstate = true)), ledgersOf(buyer), seller)

        assertTrue(r.findings.any { it.detail.contains("B2C") })
    }

    @Test
    fun `an off-slab rate is flagged`() {
        // 11,800 total with 1,000 tax implies ~9.26%, which is no slab.
        val buyer = ledger("Mehta Traders", gstin = "07AABCU9603R1ZM")
        val r = GstAuditEngine.audit(listOf(sale(gst = 1_000.0)), ledgersOf(buyer), seller)

        assertTrue(r.findings.any { it.detail.contains("not a GST slab") })
    }

    @Test
    fun `a legal slab is not flagged`() {
        val buyer = ledger("Mehta Traders", gstin = "07AABCU9603R1ZM")
        val r = GstAuditEngine.audit(listOf(sale(gst = 1_800.0)), ledgersOf(buyer), seller)

        assertTrue(r.findings.none { it.detail.contains("not a GST slab") })
    }

    @Test
    fun `GST checks do not fire on a contra`() {
        // A CONTRA never reaches GSTR-1, so place-of-supply is meaningless for it.
        val v = sale(type = VoucherType.CONTRA, gst = 0.0, party = "Cash")
        val r = GstAuditEngine.audit(listOf(v), emptyMap(), seller)

        assertTrue(
            "a contra must not be asked for a place of supply",
            r.findings.none { it.severity == AuditSeverity.BLOCKS_EXPORT }
        )
    }

    @Test
    fun `a fully correct book reports clean`() {
        val buyer = ledger("Mehta Traders", gstin = "07AABCU9603R1ZM", state = "Delhi")
        val r = GstAuditEngine.audit(listOf(sale()), ledgersOf(buyer), seller)

        assertTrue("expected clean, got ${r.findings.map { it.detail }}", r.isClean)
        assertEquals(0, r.affectedVouchers)
    }

    @Test
    fun `findings are ordered by what they cost`() {
        // BLOCKS_EXPORT must sort ahead of INCOMPLETE_RECORD so the list leads with the
        // thing that stops the user filing at all.
        assertTrue(
            AuditSeverity.BLOCKS_EXPORT.ordinal < AuditSeverity.WRONG_RETURN.ordinal
        )
        assertTrue(
            AuditSeverity.WRONG_RETURN.ordinal < AuditSeverity.INCOMPLETE_RECORD.ordinal
        )
    }
}
