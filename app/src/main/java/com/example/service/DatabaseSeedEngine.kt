package com.example.service

import com.example.data.dao.AccountingDao
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds the empty chart of accounts a new book needs: ledger groups, the standard
 * ledgers, GST duty ledgers, and voucher numbering.
 *
 * Deliberately seeds NO financial data. It previously created Rs 6,00,000 of opening
 * balances, three stock items worth Rs 74,250, two named trading partners, a named bank,
 * and a complete fabricated person -- none of which the user entered, all of which
 * rendered as their own books. The stock was the worst of it: it had no Stock-in-Hand
 * ledger behind it, so a derived Balance Sheet would show Rs 74,250 of profit for a user
 * who had posted nothing.
 */
object DatabaseSeedEngine {

    suspend fun seedDefaultData(dao: AccountingDao) = withContext(Dispatchers.IO) {
        val existingUser = dao.getUserSync()
        if (existingUser != null) return@withContext

        // Voucher prefixes used to hardcode "25-26", so every invoice number printed a
        // financial year unrelated to the one the user is actually in.
        val fyShort = com.example.utils.FiscalYearUtils.currentFyLabel().removePrefix("FY ")

        // 1. Primary Ledger Groups
        val assetsGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Assets", category = LedgerCategory.ASSET)
        )
        val liabilitiesGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Liabilities", category = LedgerCategory.LIABILITY)
        )
        val equityGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Equity", category = LedgerCategory.EQUITY)
        )
        val revenueGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Revenue", category = LedgerCategory.REVENUE)
        )
        val expenseGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Expenses", category = LedgerCategory.EXPENSE)
        )

        // 2. Sub Groups
        val cashGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Cash-in-Hand", category = LedgerCategory.ASSET, parentGroupId = assetsGrp)
        )
        val bankGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Bank Accounts", category = LedgerCategory.ASSET, parentGroupId = assetsGrp)
        )
        val debtorsGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Sundry Debtors", category = LedgerCategory.ASSET, parentGroupId = assetsGrp)
        )
        val creditorsGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Sundry Creditors", category = LedgerCategory.LIABILITY, parentGroupId = liabilitiesGrp)
        )
        val dutiesTaxesGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Duties & Taxes", category = LedgerCategory.LIABILITY, parentGroupId = liabilitiesGrp)
        )
        val salesGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Sales Accounts", category = LedgerCategory.REVENUE, parentGroupId = revenueGrp)
        )
        val purchaseGrp = dao.insertLedgerGroup(
            LedgerGroupEntity(name = "Purchase Accounts", category = LedgerCategory.EXPENSE, parentGroupId = expenseGrp)
        )

        // 3. Default Chart of Accounts Ledgers
        dao.insertLedger(LedgerEntity(name = "Cash in Hand", groupId = cashGrp, groupName = "Cash-in-Hand", category = LedgerCategory.ASSET, openingBalance = 0.0, balanceType = BalanceType.DR, systemCode = com.example.data.repository.SYSTEM_LEDGER_CASH))
        dao.insertLedger(LedgerEntity(name = "Bank Account", groupId = bankGrp, groupName = "Bank Accounts", category = LedgerCategory.ASSET, openingBalance = 0.0, balanceType = BalanceType.DR, systemCode = com.example.data.repository.SYSTEM_LEDGER_BANK))
        dao.insertLedger(LedgerEntity(name = "Sales Account", groupId = salesGrp, groupName = "Sales Accounts", category = LedgerCategory.REVENUE, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "Purchase Account", groupId = purchaseGrp, groupName = "Purchase Accounts", category = LedgerCategory.EXPENSE, openingBalance = 0.0, balanceType = BalanceType.DR))

        // GST Tax Ledgers
        dao.insertLedger(LedgerEntity(name = "CGST", groupId = dutiesTaxesGrp, groupName = "Duties & Taxes", category = LedgerCategory.LIABILITY, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "SGST", groupId = dutiesTaxesGrp, groupName = "Duties & Taxes", category = LedgerCategory.LIABILITY, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "IGST", groupId = dutiesTaxesGrp, groupName = "Duties & Taxes", category = LedgerCategory.LIABILITY, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "CESS", groupId = dutiesTaxesGrp, groupName = "Duties & Taxes", category = LedgerCategory.LIABILITY, openingBalance = 0.0, balanceType = BalanceType.CR))

        // Equity & General Expenses
        dao.insertLedger(LedgerEntity(name = "Capital Account", groupId = equityGrp, groupName = "Equity", category = LedgerCategory.EQUITY, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "General Expenses", groupId = expenseGrp, groupName = "Expenses", category = LedgerCategory.EXPENSE, openingBalance = 0.0, balanceType = BalanceType.DR))



        // A profile row must exist (the app reads a single "primary_user"), but it is
        // created BLANK. This used to seed a complete fabricated identity -- "Apex
        // Enterprises India", GSTIN 07AAAAA1234A1Z5, and a person named Rajesh Kumar
        // Sharma with a father's name and a date of birth -- which a new user could not
        // distinguish from data they had entered, and which printed on tax invoices and
        // GST exports as if it were theirs.
        dao.saveUser(
            UserEntity(
                phoneNumber = "",
                token = "",
                isLoggedIn = false,
                businessType = BusinessType.TRADING,
                enableInventory = true
            )
        )

        // 5. Default Voucher Type Configurations
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "SALES", prefix = "INV/$fyShort/", nextNumber = 1001, description = "Customer Sales Invoices"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PURCHASE", prefix = "PUR/$fyShort/", nextNumber = 2001, description = "Supplier Purchase Bills"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "RECEIPT", prefix = "REC/$fyShort/", nextNumber = 3001, description = "Cash & Bank Receipts from Customers"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PAYMENT", prefix = "PAY/$fyShort/", nextNumber = 4001, description = "Supplier Payments & Expense Payments"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "JOURNAL", prefix = "JRN/$fyShort/", nextNumber = 5001, description = "General Journal Adjustments"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "CONTRA", prefix = "CTR/$fyShort/", nextNumber = 6001, description = "Cash & Bank Transfers"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "SALES_RETURN", prefix = "SRN/$fyShort/", nextNumber = 7001, description = "Credit Notes / Sales Returns"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PURCHASE_RETURN", prefix = "PRN/$fyShort/", nextNumber = 8001, description = "Debit Notes / Purchase Returns"))
    }
}
