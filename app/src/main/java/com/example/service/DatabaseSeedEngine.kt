package com.example.service

import com.example.data.dao.AccountingDao
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seed Engine Service responsible for seeding default chart of accounts,
 * GST tax ledgers, default business profile, and sample trading/service stock data.
 */
object DatabaseSeedEngine {

    suspend fun seedDefaultData(dao: AccountingDao) = withContext(Dispatchers.IO) {
        val existingUser = dao.getUserSync()
        if (existingUser != null) return@withContext

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
        dao.insertLedger(LedgerEntity(name = "Cash in Hand", groupId = cashGrp, openingBalance = 50000.0, balanceType = BalanceType.DR))
        dao.insertLedger(LedgerEntity(name = "HDFC Bank Ltd", groupId = bankGrp, openingBalance = 250000.0, balanceType = BalanceType.DR))
        dao.insertLedger(LedgerEntity(name = "Sales Account", groupId = salesGrp, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "Purchase Account", groupId = purchaseGrp, openingBalance = 0.0, balanceType = BalanceType.DR))

        // GST Tax Ledgers
        dao.insertLedger(LedgerEntity(name = "CGST", groupId = dutiesTaxesGrp, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "SGST", groupId = dutiesTaxesGrp, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "IGST", groupId = dutiesTaxesGrp, openingBalance = 0.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "CESS", groupId = dutiesTaxesGrp, openingBalance = 0.0, balanceType = BalanceType.CR))

        // Equity & General Expenses
        dao.insertLedger(LedgerEntity(name = "Capital Account", groupId = equityGrp, openingBalance = 300000.0, balanceType = BalanceType.CR))
        dao.insertLedger(LedgerEntity(name = "General Expenses", groupId = expenseGrp, openingBalance = 0.0, balanceType = BalanceType.DR))

        // Debtors & Creditors
        dao.insertLedger(LedgerEntity(name = "Apex Traders (Customer)", groupId = debtorsGrp, openingBalance = 0.0, balanceType = BalanceType.DR))
        dao.insertLedger(LedgerEntity(name = "Royal Suppliers (Vendor)", groupId = creditorsGrp, openingBalance = 0.0, balanceType = BalanceType.CR))

        // Inventory Stock Items
        dao.insertInventoryItem(InventoryItemEntity(name = "Wireless Optical Mouse", unit = "Pcs", hsnCode = "8471", gstRate = 18.0, stockQty = 45.0, avgCostPrice = 450.0, sellingPrice = 750.0))
        dao.insertInventoryItem(InventoryItemEntity(name = "Mechanical Keyboard", unit = "Box", hsnCode = "8471", gstRate = 18.0, stockQty = 20.0, avgCostPrice = 1800.0, sellingPrice = 2800.0))
        dao.insertInventoryItem(InventoryItemEntity(name = "A4 Printer Paper Rim", unit = "Nos", hsnCode = "4802", gstRate = 12.0, stockQty = 100.0, avgCostPrice = 180.0, sellingPrice = 260.0))

        // Default Business User Profile
        dao.saveUser(
            UserEntity(
                phoneNumber = "+919876543210",
                token = "jwt_token_demo_indian_accounting_2026",
                isLoggedIn = true,
                businessType = BusinessType.TRADING,
                enableInventory = true,
                businessName = "Apex Enterprises India",
                gstin = "07AAAAA1234A1Z5",
                firstName = "Rajesh",
                middleName = "Kumar",
                surname = "Sharma",
                fatherName = "Shri Prem Nath Sharma",
                dob = "15/08/1988",
                dod = "",
                ownerName = "Rajesh Kumar Sharma"
            )
        )

        // 5. Default Voucher Type Configurations
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "SALES", prefix = "INV/25-26/", nextNumber = 1001, description = "Customer Sales Invoices"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PURCHASE", prefix = "PUR/25-26/", nextNumber = 2001, description = "Supplier Purchase Bills"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "RECEIPT", prefix = "REC/25-26/", nextNumber = 3001, description = "Cash & Bank Receipts from Customers"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PAYMENT", prefix = "PAY/25-26/", nextNumber = 4001, description = "Supplier Payments & Expense Payments"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "JOURNAL", prefix = "JRN/25-26/", nextNumber = 5001, description = "General Journal Adjustments"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "CONTRA", prefix = "CTR/25-26/", nextNumber = 6001, description = "Cash & Bank Transfers"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "SALES_RETURN", prefix = "SRN/25-26/", nextNumber = 7001, description = "Credit Notes / Sales Returns"))
        dao.insertOrUpdateVoucherConfig(VoucherTypeConfigEntity(voucherType = "PURCHASE_RETURN", prefix = "PRN/25-26/", nextNumber = 8001, description = "Debit Notes / Purchase Returns"))
    }
}
