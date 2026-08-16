package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.AppDatabase
import com.example.data.db.MIGRATION_8_9
import com.example.data.db.MIGRATION_10_11
import com.example.data.db.MIGRATION_9_10
import com.example.data.db.MIGRATION_14_15
import com.example.data.db.MIGRATION_15_16
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first migration test in this codebase.
 *
 * It exists because `fallbackToDestructiveMigration()` was removed: a mismatched
 * migration now fails loudly on launch instead of silently dropping every table. Loud is
 * correct for an accounting app, but it means a broken migration crashes real users, so
 * migrations have to be proven against a real old database before shipping.
 *
 * Requires a connected device or emulator:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * 8 → 9 realigns `ledgers.groupName`/`category` with the group `groupId` points at.
     * Seeds a v8 database in exactly the broken state real devices are in: correct
     * groupId, denormalised columns left at the entity defaults.
     */
    @Test
    fun migrate8To9_realignsDenormalisedGroupColumns() {
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO ledger_groups (id, name, category, parentGroupId) " +
                    "VALUES (1, 'Cash-in-Hand', 'ASSET', NULL), (2, 'Duties & Taxes', 'LIABILITY', NULL)"
            )
            // groupId is right; groupName/category hold the defaults the seed never overrode.
            execSQL(
                "INSERT INTO ledgers (id, name, groupId, groupName, category, openingBalance, " +
                    "balanceType, currentBalance, pincode, city, state, country, gstin) VALUES " +
                    "(10, 'Cash in Hand', 1, 'General Ledgers', 'EXPENSE', 50000.0, 'DR', 0.0, '', '', '', 'India', ''), " +
                    "(11, 'CGST', 2, 'General Ledgers', 'EXPENSE', 0.0, 'CR', 0.0, '', '', '', 'India', '')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        db.query("SELECT groupName, category FROM ledgers WHERE id = 10").use { c ->
            c.moveToFirst()
            assertEquals("Cash-in-Hand", c.getString(0))
            assertEquals("ASSET", c.getString(1))
        }
        db.query("SELECT groupName, category FROM ledgers WHERE id = 11").use { c ->
            c.moveToFirst()
            assertEquals("Duties & Taxes", c.getString(0))
            assertEquals("LIABILITY", c.getString(1))
        }
    }

    /**
     * A ledger whose group row is missing must be left untouched.
     *
     * Both columns are NOT NULL, so an unguarded correlated subquery would write NULL and
     * abort the migration — which, with destructive fallback removed, means the app fails
     * to open at all. This pins the `WHERE EXISTS` guard.
     */
    @Test
    fun migrate8To9_leavesOrphanedLedgerUntouched() {
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO ledgers (id, name, groupId, groupName, category, openingBalance, " +
                    "balanceType, currentBalance, pincode, city, state, country, gstin) VALUES " +
                    "(99, 'Orphan Ledger', 4242, 'General Ledgers', 'EXPENSE', 0.0, 'DR', 0.0, '', '', '', 'India', '')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        db.query("SELECT groupName, category FROM ledgers WHERE id = 99").use { c ->
            c.moveToFirst()
            assertEquals("General Ledgers", c.getString(0))
            assertEquals("EXPENSE", c.getString(1))
        }
    }

    /**
     * 9 → 10 adds `systemCode` and merges the duplicate cash ledger that every
     * Receipt/Payment/Contra created. The seeded "Cash in Hand" holds the opening
     * balance; the runtime-created "Cash-in-hand" holds all the movement.
     */
    @Test
    fun migrate9To10_mergesTheDuplicateCashLedger() {
        helper.createDatabase(dbName, 9).apply {
            execSQL(
                "INSERT INTO ledger_groups (id, name, category, parentGroupId) VALUES " +
                    "(1, 'Cash-in-Hand', 'ASSET', NULL), (2, 'Cash-in-hand', 'ASSET', NULL)"
            )
            execSQL(
                "INSERT INTO ledgers (id, name, groupId, groupName, category, openingBalance, " +
                    "balanceType, currentBalance, pincode, city, state, country, gstin) VALUES " +
                    "(10, 'Cash in Hand', 1, 'Cash-in-Hand', 'ASSET', 50000.0, 'DR', 0.0, '', '', '', 'India', ''), " +
                    "(11, 'Cash-in-hand', 2, 'Cash-in-hand', 'ASSET', 0.0, 'DR', 0.0, '', '', '', 'India', '')"
            )
            execSQL(
                "INSERT INTO vouchers (id, voucherNo, voucherType, date, partyName, totalAmount, " +
                    "gstAmount, isInterstate, narration, isSynced, tags) VALUES " +
                    "(1, 'CTR/1', 'CONTRA', 100, 'x', 7000.0, 0.0, 0, '', 0, '')"
            )
            execSQL(
                "INSERT INTO journal_entries (id, voucherId, ledgerId, debitAmount, creditAmount) " +
                    "VALUES (1, 1, 11, 0.0, 7000.0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        // One cash ledger, and it is the one carrying the user's opening balance.
        db.query(
            "SELECT id, openingBalance FROM ledgers WHERE REPLACE(REPLACE(LOWER(name),' ',''),'-','') = 'cashinhand'"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals(50000.0, c.getDouble(1), 0.001)
        }
        // The duplicate's posting was re-pointed, not dropped.
        db.query("SELECT ledgerId FROM journal_entries WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
        }
        db.query("SELECT systemCode FROM ledgers WHERE id = 10").use { c ->
            c.moveToFirst()
            assertEquals("CASH", c.getString(0))
        }
    }

    /**
     * A duplicate holding a real opening balance is NOT merged. The migration cannot ask
     * which account to keep, so anything carrying user data is left for the user to
     * resolve rather than folded away on a guess.
     */
    @Test
    fun migrate9To10_leavesADuplicateThatHoldsRealData() {
        helper.createDatabase(dbName, 9).apply {
            execSQL("INSERT INTO ledger_groups (id, name, category, parentGroupId) VALUES (1, 'Cash-in-Hand', 'ASSET', NULL)")
            execSQL(
                "INSERT INTO ledgers (id, name, groupId, groupName, category, openingBalance, " +
                    "balanceType, currentBalance, pincode, city, state, country, gstin) VALUES " +
                    "(10, 'Cash in Hand', 1, 'Cash-in-Hand', 'ASSET', 50000.0, 'DR', 0.0, '', '', '', 'India', ''), " +
                    "(11, 'Cash-in-hand', 1, 'Cash-in-Hand', 'ASSET', 12000.0, 'DR', 0.0, '', '', '', 'India', '')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        db.query("SELECT COUNT(*) FROM ledgers").use { c ->
            c.moveToFirst()
            assertEquals("the ledger holding 12,000 must survive", 2, c.getInt(0))
        }
    }

    /**
     * 10 → 11 splits the shared GST ledgers so output tax and input credit stop netting
     * against each other inside one balance.
     */
    @Test
    fun migrate10To11_splitsSharedGstLedgers() {
        helper.createDatabase(dbName, 10).apply {
            execSQL("INSERT INTO ledger_groups (id, name, category, parentGroupId) VALUES (5, 'Duties & Taxes', 'LIABILITY', NULL)")
            execSQL(
                "INSERT INTO ledgers (id, name, groupId, groupName, category, openingBalance, " +
                    "balanceType, currentBalance, pincode, city, state, country, gstin, systemCode) VALUES " +
                    "(20, 'CGST', 5, 'Duties & Taxes', 'LIABILITY', 0.0, 'CR', 0.0, '', '', '', 'India', '', NULL)"
            )
            execSQL(
                "INSERT INTO vouchers (id, voucherNo, voucherType, date, partyName, totalAmount, " +
                    "gstAmount, isInterstate, narration, isSynced, tags) VALUES " +
                    "(1, 'INV/1', 'SALES', 100, 'c', 5900.0, 900.0, 0, '', 0, ''), " +
                    "(2, 'PUR/1', 'PURCHASE', 200, 'v', 3540.0, 540.0, 0, '', 0, '')"
            )
            execSQL(
                "INSERT INTO journal_entries (id, voucherId, ledgerId, debitAmount, creditAmount) VALUES " +
                    "(1, 1, 20, 0.0, 900.0), (2, 2, 20, 540.0, 0.0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11)

        // Output tax became a liability of 900...
        db.query(
            "SELECT SUM(je.creditAmount) FROM journal_entries je JOIN ledgers l ON je.ledgerId = l.id " +
                "WHERE l.name = 'Output CGST'"
        ).use { c -> c.moveToFirst(); assertEquals(900.0, c.getDouble(0), 0.001) }

        // ...and input credit a receivable of 540, where before the two netted to 360.
        db.query(
            "SELECT l.category, SUM(je.debitAmount) FROM journal_entries je JOIN ledgers l ON je.ledgerId = l.id " +
                "WHERE l.name = 'Input CGST'"
        ).use { c ->
            c.moveToFirst()
            assertEquals("ASSET", c.getString(0))
            assertEquals(540.0, c.getDouble(1), 0.001)
        }

        // No posting lost.
        db.query("SELECT COUNT(*) FROM journal_entries").use { c ->
            c.moveToFirst(); assertEquals(2, c.getInt(0))
        }
    }

    /** Books must survive the migration — no rows dropped. */
    @Test
    fun migrate8To9_preservesVouchers() {
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO vouchers (id, voucherNo, voucherType, date, partyName, totalAmount, " +
                    "gstAmount, isInterstate, narration, isSynced, tags) VALUES " +
                    "(1, 'SAL/25-26/1042', 'SALES', 1760000000000, 'Anand Traders', 11800.0, " +
                    "1800.0, 0, 'Steel rod supply', 0, 'q3')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        db.query("SELECT voucherNo, totalAmount, tags FROM vouchers WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals("SAL/25-26/1042", c.getString(0))
            assertEquals(11800.0, c.getDouble(1), 0.001)
            assertEquals("q3", c.getString(2))
        }
    }

    /**
     * v15 adds the business's own UPI ID.
     *
     * Until this column existed there was nowhere to record one, so the invoice and the
     * share-payment link both fabricated a payee as "<the user's mobile number>@upi" —
     * an address on a live NPCI handle that the business has never asserted it owns.
     * Existing rows must come through with a blank, never a fabricated, value.
     */
    @Test
    fun migrate14To15_addsABlankUpiIdWithoutDisturbingTheProfile() {
        helper.createDatabase(dbName, 14).apply {
            execSQL(
                "INSERT INTO users (id, phoneNumber, token, isLoggedIn, businessType, " +
                    "enableInventory, businessName, gstin, firstName, middleName, surname, " +
                    "fatherName, dob, dod, ownerName, email, address, pincode, city, state, " +
                    "gstRegistrationDate, gstStatus, constitutionOfBusiness, filingScheme) VALUES " +
                    "('primary_user', '9876543210', 'tok', 1, 'TRADING', 1, 'Anand Traders', " +
                    "'07AABCU9603R1ZM', 'Anand', '', 'Kumar', '', '', '', 'Anand Kumar', " +
                    "'a@b.com', 'Shop 4', '110001', 'Delhi', 'Delhi', '', '', '', 'MONTHLY')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        db.query("SELECT upiId, businessName, gstin, phoneNumber FROM users WHERE id = 'primary_user'").use { c ->
            c.moveToFirst()
            assertEquals("must default to blank, not to a fabricated VPA", "", c.getString(0))
            assertEquals("Anand Traders", c.getString(1))
            assertEquals("07AABCU9603R1ZM", c.getString(2))
            assertEquals("9876543210", c.getString(3))
        }
    }

    /**
     * v16 adds the declared previous-FY aggregate turnover.
     *
     * GSTR-1's `gt` and `cur_gt` were both assigned the current month's taxable value —
     * two fields with different definitions carrying an identical figure. `gt` is declared
     * rather than derived, so -1.0 ("not declared") must survive the migration and stay
     * distinguishable from a genuine 0.0.
     */
    @Test
    fun migrate15To16_addsAnUndeclaredTurnoverWithoutDisturbingTheProfile() {
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                "INSERT INTO users (id, phoneNumber, token, isLoggedIn, businessType, " +
                    "enableInventory, businessName, gstin, firstName, middleName, surname, " +
                    "fatherName, dob, dod, ownerName, email, address, pincode, city, state, " +
                    "gstRegistrationDate, gstStatus, constitutionOfBusiness, upiId, filingScheme) VALUES " +
                    "('primary_user', '9876543210', 'tok', 1, 'TRADING', 1, 'Anand Traders', " +
                    "'07AABCU9603R1ZM', 'Anand', '', 'Kumar', '', '', '', 'Anand Kumar', " +
                    "'a@b.com', 'Shop 4', '110001', 'Delhi', 'Delhi', '', '', '', " +
                    "'anand@okhdfcbank', 'MONTHLY')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.query("SELECT previousFyAggregateTurnover, upiId, businessName FROM users WHERE id = 'primary_user'").use { c ->
            c.moveToFirst()
            assertEquals(
                "must default to -1.0, which means NOT DECLARED — a derived 0.0 would be a false declaration",
                -1.0, c.getDouble(0), 0.0001
            )
            assertEquals("anand@okhdfcbank", c.getString(1))
            assertEquals("Anand Traders", c.getString(2))
        }
    }
}
