package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BusinessType {
    SERVICE,
    TRADING
}

enum class VoucherType {
    SALES,
    PURCHASE,
    RECEIPT,
    PAYMENT,
    JOURNAL,
    CONTRA,
    SALES_RETURN,
    PURCHASE_RETURN;

    val displayName: String
        get() = when (this) {
            SALES -> "Sale"
            PURCHASE -> "Purchase"
            RECEIPT -> "Receipt"
            PAYMENT -> "Payment"
            JOURNAL -> "Journal"
            CONTRA -> "Contra (Cash/Bank)"
            SALES_RETURN -> "Sales Return"
            PURCHASE_RETURN -> "Purchase Return"
        }

    companion object {
        val Sale get() = SALES
        val Purchase get() = PURCHASE
        val Receipt get() = RECEIPT
        val Payment get() = PAYMENT
        val Journal get() = JOURNAL
        val Contra get() = CONTRA
        val SalesReturn get() = SALES_RETURN
        val PurchaseReturn get() = PURCHASE_RETURN
    }
}

enum class LedgerCategory {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}

enum class BalanceType {
    DR, // Debit
    CR  // Credit
}

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String = "primary_user",
    val phoneNumber: String,
    val token: String,
    val isLoggedIn: Boolean = true,
    val businessType: BusinessType = BusinessType.TRADING,
    val enableInventory: Boolean = true,
    val businessName: String = "Apex Enterprises India",
    val gstin: String = "07AAAAA1234A1Z5",
    val firstName: String = "Apex",
    val middleName: String = "",
    val surname: String = "Owner",
    val fatherName: String = "Shri R. P. Owner",
    val dob: String = "15/08/1988",
    val dod: String = "",
    val ownerName: String = "Apex Owner",
    val email: String = "contact@apex.in",
    val address: String = "123 Commercial Complex",
    val pincode: String = "110001",
    val city: String = "New Delhi",
    val state: String = "Delhi",
    val gstRegistrationDate: String = "01/04/2021",
    val gstStatus: String = "ACTIVE",
    val constitutionOfBusiness: String = "Proprietorship"
)

@Entity(tableName = "ledger_groups")
data class LedgerGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: LedgerCategory,
    val parentGroupId: Long? = null
)

@Entity(
    tableName = "ledgers",
    foreignKeys = [
        ForeignKey(
            entity = LedgerGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("groupId")]
)
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long,
    val groupName: String = "General Ledgers",
    val category: LedgerCategory = LedgerCategory.EXPENSE,
    val openingBalance: Double = 0.0,
    val balanceType: BalanceType = BalanceType.DR,
    val currentBalance: Double = 0.0,
    val pincode: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "India",
    val gstin: String = ""
)

@Entity(tableName = "vouchers")
data class VoucherEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val voucherType: VoucherType,
    val date: Long = System.currentTimeMillis(),
    val partyName: String,
    val totalAmount: Double,
    val gstAmount: Double = 0.0,
    val isInterstate: Boolean = false,
    val narration: String = "",
    val isSynced: Boolean = false,
    val tags: String = ""
)

@Entity(
    tableName = "journal_entries",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("voucherId"), Index("ledgerId")]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    val ledgerId: Long,
    val debitAmount: Double = 0.0,
    val creditAmount: Double = 0.0
)

@Entity(tableName = "inventory_items")
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String = "Pcs", // Pcs, Box, Kg, Nos
    val hsnCode: String = "9983",
    val gstRate: Double = 18.0, // 0, 5, 12, 18, 28
    val stockQty: Double = 0.0,
    val avgCostPrice: Double = 0.0,
    val sellingPrice: Double = 0.0
)

@Entity(
    tableName = "voucher_items",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("voucherId"), Index("itemId")]
)
data class VoucherItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    val itemId: Long,
    val quantity: Double,
    val rate: Double,
    val amount: Double,
    val gstRate: Double,
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0
)

@Entity(
    tableName = "gst_tax_details",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("voucherId")]
)
data class GstTaxDetailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    val isInterstate: Boolean,
    val taxableValue: Double,
    val cgstRate: Double = 0.0,
    val sgstRate: Double = 0.0,
    val igstRate: Double = 0.0,
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "voucher_type_configs")
data class VoucherTypeConfigEntity(
    @PrimaryKey val voucherType: String,
    val prefix: String,
    val nextNumber: Long = 1001,
    val autoIncrement: Boolean = true,
    val description: String = ""
)

enum class ReconciliationStatus {
    SHORTFALL,          // Under-payment / Pending collection
    EXCESS,             // Over-payment / Advance
    UNMATCHED_PAYMENT,  // Payment or receipt with no invoice
    RESOLVED            // Manually or automatically marked resolved
}

@Entity(tableName = "reconciliation_discrepancies")
data class ReconciliationDiscrepancyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partyName: String,
    val invoiceVoucherNo: String = "",
    val invoiceVoucherId: Long? = null,
    val voucherType: VoucherType = VoucherType.SALES,
    val expectedAmount: Double = 0.0,
    val receivedAmount: Double = 0.0,
    val discrepancyAmount: Double = 0.0,
    val status: ReconciliationStatus = ReconciliationStatus.SHORTFALL,
    val discrepancyReason: String = "",
    val detectedAt: Long = System.currentTimeMillis(),
    val isResolved: Boolean = false,
    val resolutionNotes: String = ""
)
