package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.db.AppDatabase
import com.example.data.model.BalanceType
import com.example.data.model.LedgerCategory
import com.example.data.model.VoucherType
import com.example.data.repository.AccountingRepository
import com.example.service.DatabaseSeedEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Cash and Bank tiles count.
 *
 * These queries used to identify accounts by `l.name LIKE '%Cash%' OR lg.name LIKE
 * '%Cash%'`, which had three consequences and no test coverage whatsoever:
 *
 *  * a customer called "HDFC Bank Ltd" was counted as money in the bank;
 *  * anything matching both words was counted twice, so cash + bank double-counted;
 *  * `SUM(l.openingBalance)` ignored balanceType, so an overdraft read positive.
 *
 * Membership is now the ledger's GROUP with systemCode as an override. Everything here
 * runs against real SQLite because the rule lives in SQL.
 */
@RunWith(AndroidJUnit4::class)
class CashBankBalanceTest {

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

    private suspend fun cash() = db.accountingDao().getCashBalanceFlow().first()
    private suspend fun bank() = db.accountingDao().getBankBalanceFlow().first()

    @Test
    fun aSeededBookStartsAtZeroOnBothTiles() = runBlocking {
        assertEquals(0.0, cash(), 0.005)
        assertEquals(0.0, bank(), 0.005)
    }

    @Test
    fun everyBankAccountIsCounted_notJustTheSeededOne() = runBlocking {
        // The heart of it: systemCode is stamped on exactly ONE seeded bank ledger, so a
        // systemCode-only rule would hide the second and third account of a real business.
        repo.createLedger("HDFC Current A/c", "Bank Accounts", LedgerCategory.ASSET, 3_00_000.0)
        repo.createLedger("ICICI Savings A/c", "Bank Accounts", LedgerCategory.ASSET, 2_00_000.0)

        assertEquals("both user-created bank accounts must count", 5_00_000.0, bank(), 0.005)
        assertEquals("and neither may leak into cash", 0.0, cash(), 0.005)
    }

    @Test
    fun aPettyCashBoxIsCash() = runBlocking {
        repo.createLedger("Petty Cash", "Cash-in-hand", LedgerCategory.ASSET, 5_000.0)

        assertEquals(5_000.0, cash(), 0.005)
        assertEquals(0.0, bank(), 0.005)
    }

    @Test
    fun aCustomerNamedLikeABankIsNotMoneyInTheBank() = runBlocking {
        // The reported bug. A credit sale creates a Sundry Debtors ledger named after the
        // party; matching on the ledger name booked their receivable as bank balance.
        repo.createVoucher(
            voucherType = VoucherType.SALES, partyName = "HDFC Bank Ltd",
            amount = 5_00_000.0, gstRate = 0.0, isInterstate = false, narration = "credit sale"
        )

        assertEquals("a receivable is not a bank balance", 0.0, bank(), 0.005)
        assertEquals(0.0, cash(), 0.005)
    }

    @Test
    fun aSupplierNamedLikeCashIsNotCash() = runBlocking {
        repo.createVoucher(
            voucherType = VoucherType.PURCHASE, partyName = "Cashmere Textiles",
            amount = 1_00_000.0, gstRate = 0.0, isInterstate = false, narration = "credit purchase"
        )

        assertEquals(0.0, cash(), 0.005)
        assertEquals(0.0, bank(), 0.005)
    }

    @Test
    fun anOverdraftReadsNegative() = runBlocking {
        // SUM(l.openingBalance) ignored balanceType, so 2,00,000 CR displayed as
        // +2,00,000 — an overdraft shown as money in hand, wrong by 4,00,000 and in the
        // flattering direction.
        repo.createLedger(
            name = "Bank OD A/c", groupName = "Bank OD A/c", category = LedgerCategory.LIABILITY,
            openingBalance = 2_00_000.0, balanceType = BalanceType.CR
        )

        assertEquals("an overdraft is money owed, not money held", -2_00_000.0, bank(), 0.005)
    }

    @Test
    fun openingBalancesAreSignedOnBothSides() = runBlocking {
        repo.createLedger("Vault Cash", "Cash-in-hand", LedgerCategory.ASSET, 80_000.0, BalanceType.DR)
        repo.createLedger("Till Float", "Cash-in-hand", LedgerCategory.ASSET, 30_000.0, BalanceType.CR)

        assertEquals(50_000.0, cash(), 0.005)
    }

    @Test
    fun cashAndBankAreDisjointSoTheTotalCannotDoubleCount() = runBlocking {
        // "Cash/Bank Accounts" is a group the app creates itself, and it matched BOTH of
        // the old LIKE predicates. Adding the two tiles then counted it twice.
        repo.createLedger("Combined Funds", "Cash/Bank Accounts", LedgerCategory.ASSET, 1_00_000.0)

        val total = cash() + bank()
        assertEquals("counted exactly once across the two buckets", 1_00_000.0, total, 0.005)
    }

    @Test
    fun aLedgerNamedCashAtBankIsClassifiedByItsGroupNotItsName() = runBlocking {
        repo.createLedger("Cash at Bank", "Bank Accounts", LedgerCategory.ASSET, 3_00_000.0)

        assertEquals("its group says bank", 3_00_000.0, bank(), 0.005)
        assertEquals("so it must not also be cash", 0.0, cash(), 0.005)
    }

    @Test
    fun postedMovementLandsOnTheRightTile() = runBlocking {
        repo.createLedger("HDFC Current A/c", "Bank Accounts", LedgerCategory.ASSET, 1_00_000.0)
        // A cash receipt settles to the seeded CASH ledger via systemCode.
        repo.createVoucher(
            voucherType = VoucherType.RECEIPT, partyName = "Anand Traders",
            amount = 20_000.0, gstRate = 0.0, isInterstate = false,
            narration = "collected", paymentMode = "CASH"
        )

        assertEquals("the receipt is cash in hand", 20_000.0, cash(), 0.005)
        assertEquals("the bank account is untouched", 1_00_000.0, bank(), 0.005)
    }

    @Test
    fun theDrillDownDialogAgreesWithTheTiles() = runBlocking {
        // The tiles open an itemised Cash & Bank list. It used the old LIKE matching, so
        // after a partial fix the tile and the list one tap away would disagree.
        repo.createLedger("HDFC Current A/c", "Bank Accounts", LedgerCategory.ASSET, 3_00_000.0)
        repo.createLedger("Petty Cash", "Cash-in-hand", LedgerCategory.ASSET, 5_000.0)
        repo.createVoucher(
            voucherType = VoucherType.SALES, partyName = "HDFC Bank Ltd",
            amount = 5_00_000.0, gstRate = 0.0, isInterstate = false, narration = "credit sale"
        )

        val itemised = db.accountingDao().getCashAndBankLedgersFlow().first()
        assertEquals(
            "the itemisation must sum to what the tiles show",
            cash() + bank(), itemised.sumOf { it.currentBalance }, 0.005
        )
        assertEquals(
            "and the party ledger must not appear in it",
            0, itemised.count { it.name == "HDFC Bank Ltd" }
        )
    }
}
