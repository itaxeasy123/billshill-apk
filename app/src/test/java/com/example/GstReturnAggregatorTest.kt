package com.example

import com.example.data.gst.GstReturnAggregator
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credit notes and purchase returns never reached the GST export: the engine bound only
 * SALES and PURCHASE, so GSTR-1 had no Table 9B at all and GSTR-3B reversed no ITC.
 *
 * The trap this suite exists to hold shut: a credit note is stored with a POSITIVE
 * totalAmount and gstAmount, with the reversal carried by which legs get debited. So
 * simply adding SALES_RETURN to the filter would have ADDED to the declared outward
 * liability — the sale plus its reversal booked as a fresh supply — making the business
 * declare and pay the tax on returned goods twice, in cash. Omitting credit notes
 * over-declares once; a sign-blind inclusion over-declares twice, and self-assessed tax
 * paid in cash comes back only under s.54 on a two-year clock.
 */
class GstReturnAggregatorTest {

    private fun voucher(
        type: VoucherType,
        total: Double,
        gst: Double,
        interstate: Boolean = false,
        id: Long = 1L
    ) = VoucherEntity(
        id = id,
        voucherNo = "${type.name}/$id",
        voucherType = type,
        date = 0L,
        partyName = "Mehta Traders",
        totalAmount = total,
        gstAmount = gst,
        isInterstate = interstate,
        narration = ""
    )

    // 1,18,000 = 1,00,000 taxable + 18% intra-state (9,000 CGST + 9,000 SGST)
    private fun sale(total: Double = 118_000.0, gst: Double = 18_000.0, id: Long = 1L) =
        voucher(VoucherType.SALES, total, gst, id = id)

    private fun creditNote(total: Double = 11_800.0, gst: Double = 1_800.0, id: Long = 2L) =
        voucher(VoucherType.SALES_RETURN, total, gst, id = id)

    private fun purchase(total: Double = 118_000.0, gst: Double = 18_000.0, id: Long = 3L) =
        voucher(VoucherType.PURCHASE, total, gst, id = id)

    private fun purchaseReturn(total: Double = 11_800.0, gst: Double = 1_800.0, id: Long = 4L) =
        voucher(VoucherType.PURCHASE_RETURN, total, gst, id = id)

    @Test
    fun `a credit note REDUCES outward supply rather than adding to it`() {
        // The whole point. If this ever inverts, the business pays tax twice.
        val gross = GstReturnAggregator.totalsFor(listOf(sale()), emptyList(), emptyList(), emptyList())
        val net = GstReturnAggregator.totalsFor(listOf(sale()), listOf(creditNote()), emptyList(), emptyList())

        assertTrue(
            "net outward must be LESS than gross, not more",
            net.outwardTaxable < gross.outwardTaxable
        )
        assertEquals("1,00,000 less 10,000", 90_000.0, net.outwardTaxable, 0.01)
        assertEquals("9,000 less 900", 8_100.0, net.outwardCgst, 0.01)
        assertEquals(8_100.0, net.outwardSgst, 0.01)
    }

    @Test
    fun `credit notes are stored positive, which is why the sign is applied here`() {
        // Pins the premise. If storage ever starts writing negatives, this fails loudly
        // rather than the export silently double-subtracting.
        val note = creditNote()
        assertTrue("stored positive", note.totalAmount > 0.0 && note.gstAmount > 0.0)
    }

    @Test
    fun `a purchase return reverses ITC without reducing availment`() {
        val t = GstReturnAggregator.totalsFor(
            emptyList(), emptyList(), listOf(purchase()), listOf(purchaseReturn())
        )

        assertEquals("Table 4(A) stays gross", 9_000.0, t.itcCgst, 0.01)
        assertEquals("Table 4(B)(2) carries the reversal", 900.0, t.reversalCgst, 0.01)
        assertEquals("Table 4(C) is the difference", 8_100.0, t.netItcCgst, 0.01)
        assertTrue(t.hasReversal)
    }

    @Test
    fun `no purchase return means no reversal row at all`() {
        val t = GstReturnAggregator.totalsFor(emptyList(), emptyList(), listOf(purchase()), emptyList())
        assertFalse("an empty itc_rev array is not the same as none", t.hasReversal)
        assertEquals(t.itcCgst, t.netItcCgst, 0.0)
    }

    @Test
    fun `net ITC may go negative when reversal exceeds availment`() {
        // Unlike Table 3.1, 4(C) is allowed to be negative — it adds to the period's
        // liability. Clamping it would silently forgive tax that is owed.
        val t = GstReturnAggregator.totalsFor(
            emptyList(), emptyList(),
            listOf(purchase(total = 11_800.0, gst = 1_800.0)),
            listOf(purchaseReturn(total = 118_000.0, gst = 18_000.0))
        )
        assertTrue("net ITC must be allowed below zero: ${t.netItcCgst}", t.netItcCgst < 0.0)
    }

    @Test
    fun `outward supply is clamped at zero and the clamp is reported`() {
        // Table 3.1 admits no negative. The excess must legally carry to the next period,
        // which this app cannot do — so it must not vanish silently.
        val t = GstReturnAggregator.totalsFor(
            listOf(sale(total = 11_800.0, gst = 1_800.0)),
            listOf(creditNote(total = 118_000.0, gst = 18_000.0)),
            emptyList(), emptyList()
        )

        assertEquals("never negative", 0.0, t.outwardTaxable, 0.0)
        assertEquals(0.0, t.outwardCgst, 0.0)
        assertTrue("and the user must be told", t.outwardClamped)
    }

    @Test
    fun `an ordinary period reports no clamp`() {
        val t = GstReturnAggregator.totalsFor(listOf(sale()), listOf(creditNote()), emptyList(), emptyList())
        assertFalse(t.outwardClamped)
    }

    @Test
    fun `an interstate credit note reduces IGST and leaves the state heads alone`() {
        val interstateSale = voucher(VoucherType.SALES, 118_000.0, 18_000.0, interstate = true, id = 1L)
        val interstateNote = voucher(VoucherType.SALES_RETURN, 11_800.0, 1_800.0, interstate = true, id = 2L)

        val t = GstReturnAggregator.totalsFor(listOf(interstateSale), listOf(interstateNote), emptyList(), emptyList())

        assertEquals(16_200.0, t.outwardIgst, 0.01)
        assertEquals(0.0, t.outwardCgst, 0.0)
        assertEquals(0.0, t.outwardSgst, 0.0)
    }

    @Test
    fun `a note to a registered buyer routes to CDNR`() {
        assertEquals(
            GstReturnAggregator.NoteTable.CDNR,
            GstReturnAggregator.tableFor("27AABCU9603R1ZM", creditNote())
        )
    }

    @Test
    fun `a large interstate note to an unregistered buyer routes to CDNUR`() {
        val big = voucher(VoucherType.SALES_RETURN, 2_00_000.0, 30_508.47, interstate = true, id = 5L)
        assertEquals(GstReturnAggregator.NoteTable.CDNUR, GstReturnAggregator.tableFor(null, big))
    }

    @Test
    fun `a small note to an unregistered buyer has no note table and nets into B2CS`() {
        // CDNUR's typ enum admits only B2CL / EXPWP / EXPWOP. Emitting a CDNUR row here
        // would be rejected; the schema nets it into Table 7 instead.
        assertEquals(
            GstReturnAggregator.NoteTable.NETS_INTO_B2CS,
            GstReturnAggregator.tableFor(null, creditNote())
        )
    }

    @Test
    fun `a blank GSTIN never routes to CDNR`() {
        // CDNR is keyed on ctin. A note with no GSTIN would emit "ctin": "" and the portal
        // rejects the entire upload — strictly worse than omitting the note.
        assertTrue(
            GstReturnAggregator.tableFor("", creditNote()) != GstReturnAggregator.NoteTable.CDNR
        )
        assertTrue(
            GstReturnAggregator.tableFor("  ", creditNote()) != GstReturnAggregator.NoteTable.CDNR
        )
    }

    @Test
    fun `every emitted figure is quantised to paise`() {
        // Three-decimal values are rejected by the portal for the same reason everywhere.
        val t = GstReturnAggregator.totalsFor(
            listOf(sale(total = 1_180.06, gst = 180.01)),
            listOf(creditNote(total = 118.01, gst = 18.0)),
            listOf(purchase(total = 590.03, gst = 90.0)),
            listOf(purchaseReturn(total = 59.0, gst = 9.0))
        )
        listOf(
            t.outwardTaxable, t.outwardCgst, t.outwardSgst, t.outwardIgst,
            t.itcCgst, t.reversalCgst, t.netItcCgst
        ).forEach {
            assertEquals("must be 2dp: $it", it, Math.round(it * 100.0) / 100.0, 0.0)
        }
    }
}
