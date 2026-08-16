package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.db.AppDatabase
import com.example.data.model.LedgerCategory
import com.example.data.model.VoucherType
import com.example.data.repository.AccountingRepository
import com.example.service.DatabaseSeedEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Posts real vouchers through the real repository against a real Room database and checks
 * the books tie.
 *
 * Everything else verifying the posting engine has been static analysis or JVM tests over
 * hand-built fixtures. This exercises the actual SQL, the actual ledger resolution and the
 * actual journal writes — the one form of evidence that was missing.
 */
@RunWith(AndroidJUnit4::class)
class PostingEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AccountingRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AccountingRepository(db.accountingDao(), db)
        DatabaseSeedEngine.seedDefaultData(db.accountingDao())
    }

    @After
    fun tearDown() = db.close()

    /** Σ(debit) − Σ(credit) over every journal row. Must be zero for the books to be sound. */
    private suspend fun journalImbalance(): Double {
        val entries = db.accountingDao().getAllJournalEntriesList()
        return entries.sumOf { it.debitAmount } - entries.sumOf { it.creditAmount }
    }

    @Test
    fun intrastateSale_postsBalancedLegsWithCorrectTax() = runBlocking {
        // Rs 10,000 + 18%, entered Exclusive, so Rs 11,800 must reach the repository.
        repo.createVoucher(
            voucherType = VoucherType.SALES,
            partyName = "Anand Traders",
            amount = 11_800.0,
            gstRate = 18.0,
            isInterstate = false,
            narration = "Steel rods"
        )

        val voucher = db.accountingDao().getAllVouchersList().single()
        assertEquals(11_800.0, voucher.totalAmount, 0.005)
        assertEquals(1_800.0, voucher.gstAmount, 0.005)

        val entries = db.accountingDao().getJournalEntriesForVoucher(voucher.id)
        assertEquals("party + sales + CGST + SGST", 4, entries.size)
        assertEquals(11_800.0, entries.sumOf { it.debitAmount }, 0.005)
        assertEquals(11_800.0, entries.sumOf { it.creditAmount }, 0.005)

        // Output tax, not the shared ledger, and not input.
        val byLedger = entries.associate { e ->
            db.accountingDao().getLedgerById(e.ledgerId)!!.name to (e.debitAmount - e.creditAmount)
        }
        assertEquals(-900.0, byLedger["Output CGST"]!!, 0.005)
        assertEquals(-900.0, byLedger["Output SGST"]!!, 0.005)
        assertEquals(-10_000.0, byLedger["Sales Account"]!!, 0.005)
        assertEquals(11_800.0, byLedger["Anand Traders"]!!, 0.005)

        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun interstateSale_usesIgstAndNeverSplitsIt() = runBlocking {
        repo.createVoucher(
            voucherType = VoucherType.SALES,
            partyName = "Delhi Buyer",
            amount = 11_800.0,
            gstRate = 18.0,
            isInterstate = true,
            narration = "interstate"
        )
        val v = db.accountingDao().getAllVouchersList().single()
        val names = db.accountingDao().getJournalEntriesForVoucher(v.id)
            .map { db.accountingDao().getLedgerById(it.ledgerId)!!.name }
        assertTrue("IGST expected", names.any { it == "Output IGST" })
        assertTrue("CGST must not appear", names.none { it.contains("CGST") })
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun purchase_debitsInputTaxNotOutput() = runBlocking {
        repo.createVoucher(
            voucherType = VoucherType.PURCHASE,
            partyName = "Sharma Supplies",
            amount = 5_900.0,
            gstRate = 18.0,
            isInterstate = false,
            narration = "stock purchase"
        )
        val v = db.accountingDao().getAllVouchersList().single()
        val byLedger = db.accountingDao().getJournalEntriesForVoucher(v.id).associate { e ->
            db.accountingDao().getLedgerById(e.ledgerId)!!.name to (e.debitAmount - e.creditAmount)
        }
        // Input credit is a receivable — debited.
        assertEquals(450.0, byLedger["Input CGST"]!!, 0.005)
        assertEquals(450.0, byLedger["Input SGST"]!!, 0.005)
        assertEquals(-5_900.0, byLedger["Sharma Supplies"]!!, 0.005)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun receiptSettlesToTheChosenAccount_notTheOneTheNameHints() = runBlocking {
        // "Prakash Traders" contains "cash" — the old inference sent it to the cash drawer.
        repo.createVoucher(
            voucherType = VoucherType.RECEIPT,
            partyName = "Prakash Traders",
            amount = 5_000.0,
            gstRate = 0.0,
            narration = "on account",
            paymentMode = "BANK"
        )
        val v = db.accountingDao().getAllVouchersList().single()
        val debited = db.accountingDao().getJournalEntriesForVoucher(v.id)
            .first { it.debitAmount > 0 }
        assertEquals("Bank Account", db.accountingDao().getLedgerById(debited.ledgerId)!!.name)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun contraMovesBetweenCashAndBankAndCreatesNoDuplicateLedger() = runBlocking {
        val before = db.accountingDao().getAllLedgersList().size
        repo.createVoucher(
            voucherType = VoucherType.CONTRA,
            partyName = "Cash Deposit to Bank",
            amount = 7_000.0,
            gstRate = 0.0,
            narration = "deposit",
            paymentMode = "BANK"     // money ends up in the bank
        )
        val v = db.accountingDao().getAllVouchersList().single()
        val entries = db.accountingDao().getJournalEntriesForVoucher(v.id)
        val debited = db.accountingDao().getLedgerById(entries.first { it.debitAmount > 0 }.ledgerId)!!
        val credited = db.accountingDao().getLedgerById(entries.first { it.creditAmount > 0 }.ledgerId)!!

        assertEquals("Bank Account", debited.name)
        assertEquals("Cash in Hand", credited.name)
        // No second cash ledger — the split C2 fixed.
        assertEquals("no ledger should have been created", before, db.accountingDao().getAllLedgersList().size)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun aFullBookOfMixedVouchersStillTies() = runBlocking {
        repo.createVoucher(VoucherType.SALES, "Anand Traders", 11_800.0, 18.0, false, "sale")
        repo.createVoucher(VoucherType.PURCHASE, "Sharma Supplies", 5_900.0, 18.0, false, "purchase")
        repo.createVoucher(VoucherType.RECEIPT, "Anand Traders", 11_800.0, 0.0, false, "collected", paymentMode = "BANK")
        repo.createVoucher(VoucherType.PAYMENT, "Sharma Supplies", 5_900.0, 0.0, false, "paid", paymentMode = "BANK")
        repo.createVoucher(VoucherType.CONTRA, "withdrawal", 2_000.0, 0.0, false, "cash for float", paymentMode = "CASH")
        repo.createVoucher(VoucherType.SALES_RETURN, "Anand Traders", 1_180.0, 18.0, false, "returned")

        assertEquals("6 vouchers posted", 6, db.accountingDao().getAllVouchersList().size)
        assertEquals("the books must tie", 0.0, journalImbalance(), 0.005)
    }

    @Test
    fun amendingASaleKeepsItsNumberDateAndTaxRate() = runBlocking {
        repo.createVoucher(
            voucherType = VoucherType.SALES, partyName = "Anand Traders",
            amount = 10_500.0, gstRate = 5.0, isInterstate = false,
            narration = "original", tags = "q3"
        )
        val before = db.accountingDao().getAllVouchersList().single()

        repo.amendVoucher(
            voucherId = before.id,
            voucherType = VoucherType.SALES,
            partyName = "Anand Traders",
            amount = 10_500.0,
            gstRate = 5.0,
            isInterstate = false,
            narration = "corrected narration",
            dateMillis = before.date,
            tags = before.tags
        )
        val after = db.accountingDao().getAllVouchersList().single()

        assertEquals("voucher number must survive", before.voucherNo, after.voucherNo)
        assertEquals("date must survive", before.date, after.date)
        assertEquals("tags must survive", "q3", after.tags)
        assertEquals("5% must not become 18%", 500.0, after.gstAmount, 0.005)
        assertEquals("corrected narration", after.narration)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun deletingAVoucherLeavesNoOrphanPostings() = runBlocking {
        repo.createVoucher(VoucherType.SALES, "Anand Traders", 11_800.0, 18.0, false, "sale")
        val v = db.accountingDao().getAllVouchersList().single()

        val snapshot = repo.deleteVoucher(v.id)
        assertTrue("a snapshot must be returned so Undo can restore it", snapshot != null)
        assertEquals(0, db.accountingDao().getAllJournalEntriesList().size)
        assertEquals(0.0, journalImbalance(), 0.005)

        // Undo restores it verbatim — same id and number.
        repo.restoreDeletedVoucher(snapshot!!)
        val restored = db.accountingDao().getAllVouchersList().single()
        assertEquals(v.id, restored.id)
        assertEquals(v.voucherNo, restored.voucherNo)
        assertEquals(v.date, restored.date)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun voucherNumbersAreNeverIssuedTwice() = runBlocking {
        repeat(25) {
            repo.createVoucher(VoucherType.SALES, "Buyer $it", 1_180.0, 18.0, false, "sale $it")
        }
        val numbers = db.accountingDao().getAllVouchersList().map { it.voucherNo }
        assertEquals("all 25 numbers must be distinct", numbers.size, numbers.toSet().size)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun openingStockPostsAContraSoTheBooksStillTie() = runBlocking {
        // The quantity used to enter inventory with no posting at all.
        repo.createInventoryItem(
            name = "Widget", unit = "Pcs", hsn = "8471",
            gstRate = 18.0, cost = 50.0, price = 80.0, openingQty = 100.0
        )
        val item = db.accountingDao().getAllInventoryItemsList().single()
        assertEquals(100.0, item.stockQty, 0.001)

        val voucher = db.accountingDao().getAllVouchersList().single()
        assertEquals(VoucherType.STOCK_OPENING, voucher.voucherType)
        assertEquals(5_000.0, voucher.totalAmount, 0.005)
        assertEquals("opening stock must predate the year it opens", true, voucher.date < System.currentTimeMillis())

        assertEquals("the books must tie after opening stock", 0.0, journalImbalance(), 0.005)
    }

    @Test
    fun stockMovesTheRightWayOnSalePurchaseAndReturn() = runBlocking {
        repo.createInventoryItem("Widget", "Pcs", "8471", 18.0, 50.0, 80.0, openingQty = 100.0)
        val id = db.accountingDao().getAllInventoryItemsList().single().id

        repo.createVoucher(VoucherType.PURCHASE, "Supplier", 1_180.0, 18.0, false, "buy",
            selectedItemId = id, itemQuantity = 20.0)
        assertEquals(120.0, db.accountingDao().getInventoryItemById(id)!!.stockQty, 0.001)

        repo.createVoucher(VoucherType.SALES, "Buyer", 3_540.0, 18.0, false, "sell",
            selectedItemId = id, itemQuantity = 30.0)
        assertEquals(90.0, db.accountingDao().getInventoryItemById(id)!!.stockQty, 0.001)

        // A sales return brings stock BACK — the case delete used to miss entirely.
        repo.createVoucher(VoucherType.SALES_RETURN, "Buyer", 1_180.0, 18.0, false, "returned",
            selectedItemId = id, itemQuantity = 10.0)
        assertEquals(100.0, db.accountingDao().getInventoryItemById(id)!!.stockQty, 0.001)

        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun editingAVoucherDoesNotUnSellItsStock() = runBlocking {
        repo.createInventoryItem("Widget", "Pcs", "8471", 18.0, 50.0, 80.0, openingQty = 100.0)
        val id = db.accountingDao().getAllInventoryItemsList().single().id

        repo.createVoucher(VoucherType.SALES, "Buyer", 3_540.0, 18.0, false, "sell",
            selectedItemId = id, itemQuantity = 30.0)
        assertEquals(70.0, db.accountingDao().getInventoryItemById(id)!!.stockQty, 0.001)

        val sale = db.accountingDao().getAllVouchersList().first { it.voucherType == VoucherType.SALES }
        // Narration-only edit, exactly as both dialogs call it: no item, no quantity.
        repo.amendVoucher(
            voucherId = sale.id, voucherType = VoucherType.SALES, partyName = "Buyer",
            amount = 3_540.0, gstRate = 18.0, isInterstate = false,
            narration = "corrected", dateMillis = sale.date, tags = sale.tags
        )

        assertEquals(
            "stock must stay sold — an edit reversed 30 and re-applied only 1",
            70.0, db.accountingDao().getInventoryItemById(id)!!.stockQty, 0.001
        )
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun aPettyExpenseLandsInExpensesAndNotOnALiability() = runBlocking {
        // The Dashboard's petty-cash button posted a PAYMENT whose partyName was the
        // expense CATEGORY. getOrCreatePartyLedger files a PAYMENT counterparty under
        // Sundry Creditors as a LIABILITY, and the PAYMENT branch then debits it — so
        // Rs 1,000 of tea became a debit to a liability named "Refreshments & Hospitality",
        // absent from the P&L, the expense bars and the Reports donut, while the Balance
        // Sheet still tied perfectly. An expense that disappears without breaking a
        // balance check is exactly what no report in the app could have caught.
        repo.postPettyExpense(
            category = "Refreshments & Hospitality",
            amount = 1_000.0,
            narration = "Petty Expense: tea for the shop"
        )

        val ledger = db.accountingDao().getLedgerByName("Refreshments & Hospitality")!!
        assertEquals(
            "a petty expense must be an EXPENSE, not a liability",
            LedgerCategory.EXPENSE, ledger.category
        )

        val entries = db.accountingDao().getAllJournalEntriesList()
        val expenseLeg = entries.single { it.ledgerId == ledger.id }
        assertEquals("and it must be DEBITED", 1_000.0, expenseLeg.debitAmount, 0.005)
        assertEquals(0.0, expenseLeg.creditAmount, 0.005)

        val cash = db.accountingDao().getLedgerBySystemCode("CASH")!!
        val cashLeg = entries.single { it.ledgerId == cash.id }
        assertEquals("cash must be credited", 1_000.0, cashLeg.creditAmount, 0.005)

        assertEquals("and the books still tie", 0.0, journalImbalance(), 0.005)
    }

    // ---- Ledger deletion -------------------------------------------------------------
    //
    // deleteLedger used to delete the ledger's journal entries and then the ledger,
    // stepping around the ON DELETE RESTRICT that exists to stop exactly that. The other
    // legs of every affected voucher survived, so a balanced book was left permanently
    // out with no undo while the toast reported success. Nothing covered it.

    @Test
    fun deletingALedgerWithPostingsIsRefusedAndTheBooksStillTie() = runBlocking {
        repo.createVoucher(
            voucherType = VoucherType.SALES, partyName = "Anand Traders",
            amount = 11_800.0, gstRate = 18.0, isInterstate = false, narration = "sale"
        )
        assertEquals(0.0, journalImbalance(), 0.005)

        val party = db.accountingDao().getLedgerByName("Anand Traders")!!
        val entriesBefore = db.accountingDao().getAllJournalEntriesList().size
        assertTrue("the fixture must actually post to this ledger", entriesBefore > 0)

        val result = repo.deleteLedger(party.id)

        assertTrue(
            "a ledger carrying postings must be refused, was $result",
            result is AccountingRepository.LedgerDeleteResult.HasPostings
        )
        assertTrue("the ledger must survive", db.accountingDao().getLedgerById(party.id) != null)
        assertEquals(
            "not one journal row may be destroyed",
            entriesBefore, db.accountingDao().getAllJournalEntriesList().size
        )
        assertEquals("the books must still tie", 0.0, journalImbalance(), 0.005)
    }

    @Test
    fun refusingALedgerReportsWhatIsBlockingIt() = runBlocking {
        // Two vouchers against one party: the message has to name vouchers, not entries,
        // because vouchers are what the user has to go and act on.
        repo.createVoucher(VoucherType.SALES, "Anand Traders", 11_800.0, 18.0, false, "one")
        repo.createVoucher(VoucherType.SALES, "Anand Traders", 5_900.0, 18.0, false, "two")

        val party = db.accountingDao().getLedgerByName("Anand Traders")!!
        val result = repo.deleteLedger(party.id)

        result as AccountingRepository.LedgerDeleteResult.HasPostings
        assertEquals("both vouchers must be counted", 2, result.voucherCount)
        assertTrue("entries must outnumber vouchers", result.entryCount >= result.voucherCount)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun deletingAnUnusedLedgerStillWorks() = runBlocking {
        // The legitimate case the refusal must not break: a ledger created by mistake.
        val id = repo.createLedger(
            name = "Typo Ledger", groupName = "Indirect Expenses",
            category = LedgerCategory.EXPENSE
        )

        assertEquals(
            "a ledger with no postings must still be deletable",
            AccountingRepository.LedgerDeleteResult.Deleted, repo.deleteLedger(id)
        )
        assertTrue(db.accountingDao().getLedgerById(id) == null)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun aSystemLedgerIsNeverDeletable() = runBlocking {
        // Cash seeds with zero postings, so before the guard it deleted cleanly — and
        // getOrCreateSystemLedger then recreated an empty one under its default name,
        // leaving the books looking plausible with the history gone.
        val cash = db.accountingDao().getLedgerBySystemCode("CASH")!!

        val result = repo.deleteLedger(cash.id)

        assertEquals(
            "a system ledger must never be deletable",
            AccountingRepository.LedgerDeleteResult.SystemLedger("CASH"), result
        )
        assertTrue(db.accountingDao().getLedgerBySystemCode("CASH") != null)
        assertEquals(0.0, journalImbalance(), 0.005)
    }

    @Test
    fun deletingTheVouchersFirstThenTheLedgerIsTheWorkingRoute() = runBlocking {
        // The escape hatch the refusal points the user at has to actually work end to end.
        repo.createVoucher(VoucherType.SALES, "One Off Buyer", 11_800.0, 18.0, false, "sale")
        val party = db.accountingDao().getLedgerByName("One Off Buyer")!!

        val sale = db.accountingDao().getAllVouchersList().first { it.voucherType == VoucherType.SALES }
        repo.deleteVoucher(sale.id)

        assertEquals(
            "with its vouchers gone the ledger is childless and deletable",
            AccountingRepository.LedgerDeleteResult.Deleted, repo.deleteLedger(party.id)
        )
        assertTrue(db.accountingDao().getLedgerById(party.id) == null)
        assertEquals("and the books still tie", 0.0, journalImbalance(), 0.005)
    }
}
