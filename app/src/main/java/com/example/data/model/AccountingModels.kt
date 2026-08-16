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
    /** Opening stock brought in when an item is created with a quantity already on hand. */
    STOCK_OPENING,
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
            STOCK_OPENING -> "Opening Stock"
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
    // Every field below defaults to blank on purpose. These used to hold a complete,
    // realistic identity -- "Apex Enterprises India", GSTIN 07AAAAA1234A1Z5, a person
    // named Rajesh Kumar Sharma with a father's name and a date of birth, a Delhi
    // address, and gstStatus "ACTIVE". Because UserEntity is constructed partially in
    // several places, those defaults silently became a real user's saved profile, and
    // then printed on tax invoices, GSTR exports and the GST certificate as if the user
    // had entered them. A blank field can be detected and prompted for; a plausible
    // fake one cannot.
    val businessName: String = "",
    val gstin: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val surname: String = "",
    val fatherName: String = "",
    val dob: String = "",
    val dod: String = "",
    val ownerName: String = "",
    val email: String = "",
    val address: String = "",
    val pincode: String = "",
    val city: String = "",
    val state: String = "",
    val gstRegistrationDate: String = "",
    // Blank, not "ACTIVE": the app has no way to verify GST registration status, and a
    // green ACTIVE badge asserting it is a claim the app cannot support.
    val gstStatus: String = "",
    /**
     * The business's own UPI ID, e.g. "shop@okhdfcbank".
     *
     * Nothing recorded one, so the invoice and the payment link both built a payee as
     * "<mobile number>@upi" — an address the business has never asserted it owns, on a
     * live NPCI handle, printed under "SCAN TO PAY". Blank by default: no payment
     * details are shown until the user supplies a real one.
     */
    val upiId: String = "",
    /**
     * Aggregate turnover of the PRECEDING financial year — GSTR-1's `gt`.
     *
     * User-declared, not derived: s.2(6) aggregate turnover is a PAN-level, all-India
     * figure spanning every GSTIN under the same PAN and including exempt, nil-rated,
     * non-GST and export supplies, none of which this single-GSTIN book distinguishes.
     * -1.0 means "not declared", which is deliberately distinguishable from a genuine 0.0.
     */
    val previousFyAggregateTurnover: Double = -1.0,
    val constitutionOfBusiness: String = "",
    /**
     * GST filing scheme. QRMP (Quarterly Return, Monthly Payment) is available to
     * taxpayers with aggregate turnover up to Rs 5 crore and changes the due dates
     * entirely — GSTR-1 quarterly on the 13th of the month following the quarter, with
     * monthly payment via PMT-06 by the 25th, instead of the monthly 11th/20th. The
     * deadlines shown were hardcoded to the monthly scheme, so a QRMP filer was given
     * the wrong dates every month (H14).
     */
    val filingScheme: String = "MONTHLY"
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
    val gstin: String = "",
    /**
     * Stable identity for the ledgers the posting engine must always find: "CASH",
     * "BANK". Resolution used to be by exact, case-sensitive name — and the seed writes
     * "Cash in Hand" while the repository looks up "Cash-in-hand", which never matches.
     * Every Receipt, Payment and Contra therefore created a SECOND cash ledger, leaving
     * the Trial Balance showing one cash account holding the opening balance and another
     * holding every movement, the latter with a credit balance. A code cannot be
     * misspelt by a caller.
     */
    val systemCode: String? = null
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
    val tags: String = "",
    /**
     * How the voucher settled: CASH, BANK, or CREDIT (on account).
     *
     * Whether a Receipt or Payment hit cash or bank was inferred from a SUBSTRING of the
     * party's name — `partyName.contains("cash")` — so a receipt from "Prakash Traders"
     * or "Cashmere Textiles" went to the cash drawer, and a genuine cash sale from a
     * party without the word in their name went to the bank (H3). How a payment was made
     * is a property of the transaction and cannot be recovered from who it was with.
     *
     * Defaults to CASH so existing behaviour is preserved for vouchers that never
     * recorded one; MIGRATION_12_13 back-derives the real value from the ledger each
     * voucher actually posted to.
     */
    val paymentMode: String = "CASH"
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
    /**
     * The cost basis of this line at the moment it was posted.
     *
     * Historical stock movement was valued at the item's CURRENT `avgCostPrice`, so one
     * purchase at a different price silently revalued every past movement and the
     * derived opening-stock figure drifted away from what was actually posted — putting
     * the Balance Sheet out by the drift. Freezing the cost on the line makes the
     * roll-back exact at every as-on date.
     *
     * Distinct from [rate], which on an outward line is the SELLING price.
     */
    val costRate: Double = 0.0,
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
