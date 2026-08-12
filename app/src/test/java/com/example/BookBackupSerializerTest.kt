package com.example

import com.example.data.model.*
import com.example.utils.BookBackupSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for C5/C6/C7/C11 — the backup that could not restore the books.
 *
 * Runs under Robolectric because `org.json` is a non-functional stub in plain JVM unit
 * tests; Robolectric supplies the real implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookBackupSerializerTest {

    private fun sampleBooks() = BookBackupSerializer.BookSnapshot(
        user = UserEntity(
            phoneNumber = "+919812345678",
            token = "tkn",
            businessName = "Batra Sons",
            gstin = "23ADOPS9429A1ZE",
            ownerName = "R. Batra",
            city = "Gwalior",
            state = "Madhya Pradesh"
        ),
        ledgerGroups = listOf(
            LedgerGroupEntity(id = 1, name = "Current Assets", category = LedgerCategory.ASSET),
            LedgerGroupEntity(id = 2, name = "Sundry Debtors", category = LedgerCategory.ASSET, parentGroupId = 1)
        ),
        ledgers = listOf(
            LedgerEntity(
                id = 10, name = "Cash in Hand", groupId = 1, groupName = "Current Assets",
                category = LedgerCategory.ASSET, openingBalance = 50_000.0, balanceType = BalanceType.DR
            ),
            LedgerEntity(
                id = 11, name = "Anand Traders", groupId = 2, groupName = "Sundry Debtors",
                category = LedgerCategory.ASSET, gstin = "23BNJPS3408M1ZP", state = "Madhya Pradesh"
            )
        ),
        inventoryItems = listOf(
            InventoryItemEntity(id = 20, name = "Steel Rod", unit = "Kg", hsnCode = "7214",
                gstRate = 18.0, stockQty = 120.0, avgCostPrice = 55.0, sellingPrice = 72.0)
        ),
        vouchers = listOf(
            VoucherEntity(
                id = 100, voucherNo = "SAL/25-26/1042", voucherType = VoucherType.SALES,
                date = 1_760_000_000_000L, partyName = "Anand Traders", totalAmount = 11_800.0,
                gstAmount = 1_800.0, isInterstate = false, narration = "Steel rod supply",
                tags = "urgent,q3"
            )
        ),
        journalEntries = listOf(
            JournalEntryEntity(id = 200, voucherId = 100, ledgerId = 11, debitAmount = 11_800.0),
            JournalEntryEntity(id = 201, voucherId = 100, ledgerId = 10, creditAmount = 11_800.0)
        ),
        voucherItems = listOf(
            VoucherItemEntity(id = 300, voucherId = 100, itemId = 20, quantity = 100.0,
                rate = 100.0, amount = 10_000.0, gstRate = 18.0, cgstAmount = 900.0, sgstAmount = 900.0)
        ),
        gstTaxDetails = listOf(
            GstTaxDetailEntity(id = 400, voucherId = 100, isInterstate = false, taxableValue = 10_000.0,
                cgstRate = 9.0, sgstRate = 9.0, cgstAmount = 900.0, sgstAmount = 900.0)
        ),
        voucherConfigs = listOf(
            VoucherTypeConfigEntity(voucherType = "SALES", prefix = "SAL/25-26/", nextNumber = 1043L)
        ),
        reconciliations = listOf(
            ReconciliationDiscrepancyEntity(id = 500, partyName = "Anand Traders",
                invoiceVoucherNo = "SAL/25-26/1042", invoiceVoucherId = 100,
                expectedAmount = 11_800.0, receivedAmount = 11_000.0, discrepancyAmount = 800.0,
                status = ReconciliationStatus.SHORTFALL, detectedAt = 1_760_100_000_000L)
        )
    )

    @Test
    fun `books survive a full write-read round trip`() {
        val original = sampleBooks()
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(original, timestampMillis = 1_760_200_000_000L)
        )

        assertEquals(original.user, restored.user)
        assertEquals(original.ledgerGroups, restored.ledgerGroups)
        assertEquals(original.ledgers, restored.ledgers)
        assertEquals(original.inventoryItems, restored.inventoryItems)
        assertEquals(original.vouchers, restored.vouchers)
        assertEquals(original.journalEntries, restored.journalEntries)
        assertEquals(original.voucherItems, restored.voucherItems)
        assertEquals(original.gstTaxDetails, restored.gstTaxDetails)
        assertEquals(original.voucherConfigs, restored.voucherConfigs)
        assertEquals(original.reconciliations, restored.reconciliations)
    }

    @Test
    fun `voucher number date and tags are preserved`() {
        // The old restore replayed vouchers through createVoucher, which reissued the
        // number and restamped the date to the moment of import, and had no tags param.
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        val v = restored.vouchers.single()
        assertEquals("SAL/25-26/1042", v.voucherNo)
        assertEquals(1_760_000_000_000L, v.date)
        assertEquals("urgent,q3", v.tags)
    }

    @Test
    fun `ledger group category and opening balance are preserved`() {
        // The old restore called getLedgerByNameOrCreate(name, "Sundry Debtors", ASSET)
        // for every ledger, so Cash in Hand came back as a debtor with a zero opening.
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        val cash = restored.ledgers.first { it.name == "Cash in Hand" }
        assertEquals("Current Assets", cash.groupName)
        assertEquals(1L, cash.groupId)
        assertEquals(50_000.0, cash.openingBalance, 0.001)
        assertEquals(BalanceType.DR, cash.balanceType)
    }

    @Test
    fun `foreign keys still resolve after restore`() {
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        val voucherIds = restored.vouchers.map { it.id }.toSet()
        val ledgerIds = restored.ledgers.map { it.id }.toSet()
        val itemIds = restored.inventoryItems.map { it.id }.toSet()
        val groupIds = restored.ledgerGroups.map { it.id }.toSet()

        restored.journalEntries.forEach {
            assertTrue("journal voucherId ${it.voucherId}", it.voucherId in voucherIds)
            assertTrue("journal ledgerId ${it.ledgerId}", it.ledgerId in ledgerIds)
        }
        restored.voucherItems.forEach {
            assertTrue("item voucherId ${it.voucherId}", it.voucherId in voucherIds)
            assertTrue("item itemId ${it.itemId}", it.itemId in itemIds)
        }
        restored.gstTaxDetails.forEach {
            assertTrue("gst voucherId ${it.voucherId}", it.voucherId in voucherIds)
        }
        restored.ledgers.forEach {
            assertTrue("ledger groupId ${it.groupId}", it.groupId in groupIds)
        }
        restored.ledgerGroups.mapNotNull { it.parentGroupId }.forEach {
            assertTrue("parent groupId $it", it in groupIds)
        }
    }

    @Test
    fun `gst tax detail keeps its own rate rather than back-deriving one`() {
        // The old import recomputed rate as gstAmount/(total-gstAmount)*100, which is
        // lossy and produced 18.000000000000004-style rates on rounded amounts.
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        val g = restored.gstTaxDetails.single()
        assertEquals(9.0, g.cgstRate, 0.0)
        assertEquals(9.0, g.sgstRate, 0.0)
        assertEquals(10_000.0, g.taxableValue, 0.0)
    }

    @Test
    fun `inventory stock quantity and cost survive`() {
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        val item = restored.inventoryItems.single()
        assertEquals(120.0, item.stockQty, 0.001)
        assertEquals(55.0, item.avgCostPrice, 0.001)
    }

    @Test
    fun `voucher numbering counter survives so the next voucher does not collide`() {
        val restored = BookBackupSerializer.fromJson(
            BookBackupSerializer.toJson(sampleBooks(), 0L)
        )
        assertEquals(1043L, restored.voucherConfigs.single().nextNumber)
    }

    @Test
    fun `the old lossy format is refused rather than half-imported`() {
        val legacy = """{"app":"TallyMobileBackup","timestamp":1,"vouchers":[]}"""
        val e = assertThrows(BookBackupSerializer.IncompatibleBackupException::class.java) {
            BookBackupSerializer.fromJson(legacy)
        }
        assertTrue(e.message!!.contains("older backup"))
    }

    @Test
    fun `a newer format version is refused`() {
        val future = """{"format":"bill-shill.books","formatVersion":99}"""
        assertThrows(BookBackupSerializer.IncompatibleBackupException::class.java) {
            BookBackupSerializer.fromJson(future)
        }
    }

    @Test
    fun `garbage input is refused`() {
        assertThrows(BookBackupSerializer.IncompatibleBackupException::class.java) {
            BookBackupSerializer.fromJson("not json at all")
        }
    }

    @Test
    fun `empty books round-trip without inventing rows`() {
        val empty = BookBackupSerializer.BookSnapshot(
            null, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
        val restored = BookBackupSerializer.fromJson(BookBackupSerializer.toJson(empty, 0L))
        assertEquals(0, restored.rowCount())
        assertEquals(null, restored.user)
    }

    @Test
    fun `the file records which tables it deliberately omits`() {
        val json = BookBackupSerializer.toJson(sampleBooks(), 0L)
        // The omission of operational logs must be visible in the file, not silent.
        BookBackupSerializer.EXCLUDED_TABLES.forEach {
            assertTrue("excluded table $it named in file", json.contains(it))
        }
        assertNotNull(json)
    }
}
