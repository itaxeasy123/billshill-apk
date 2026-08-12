package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.AppDatabase
import com.example.data.db.MIGRATION_8_9
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
}
