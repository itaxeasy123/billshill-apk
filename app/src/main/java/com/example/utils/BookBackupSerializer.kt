package com.example.utils

import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-fidelity serialiser for the books.
 *
 * The previous backup format captured four tables and dropped most of their columns,
 * then "restored" by replaying vouchers through `createVoucher`. That is a re-entry,
 * not a restore: it reissued every voucher number, restamped every date to the moment
 * of import, back-derived GST rates from rounded amounts, discarded tags, and recreated
 * every ledger as a Sundry Debtor under General Ledgers. Running it twice doubled the
 * books, because nothing was cleared first.
 *
 * This format writes every books-bearing table with every column, **including primary
 * keys**, so foreign keys still resolve after a restore and the restored set of books is
 * the same set of books.
 *
 * Deliberately excluded — operational logs, not books, and safe to lose:
 *   `sync_logs`, `crash_logs`, `monthly_archives`.
 * They are named in [EXCLUDED_TABLES] and recorded in the file itself, so the omission
 * is visible to anyone reading a backup rather than silent.
 */
object BookBackupSerializer {

    const val FORMAT = "bill-shill.books"

    /**
     * Bump when the on-disk shape changes. [restore] refuses anything it does not
     * recognise rather than importing a partial, plausible-looking set of books.
     */
    const val FORMAT_VERSION = 1

    val EXCLUDED_TABLES = listOf("sync_logs", "crash_logs", "monthly_archives")

    /** Everything needed to reconstruct the books, in dependency order. */
    data class BookSnapshot(
        val user: UserEntity?,
        val ledgerGroups: List<LedgerGroupEntity>,
        val ledgers: List<LedgerEntity>,
        val inventoryItems: List<InventoryItemEntity>,
        val vouchers: List<VoucherEntity>,
        val journalEntries: List<JournalEntryEntity>,
        val voucherItems: List<VoucherItemEntity>,
        val gstTaxDetails: List<GstTaxDetailEntity>,
        val voucherConfigs: List<VoucherTypeConfigEntity>,
        val reconciliations: List<ReconciliationDiscrepancyEntity>
    ) {
        fun rowCount(): Int = (if (user != null) 1 else 0) + ledgerGroups.size + ledgers.size +
            inventoryItems.size + vouchers.size + journalEntries.size + voucherItems.size +
            gstTaxDetails.size + voucherConfigs.size + reconciliations.size
    }

    // ---------------- write ----------------

    fun toJson(snapshot: BookSnapshot, timestampMillis: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("timestamp", timestampMillis)
        root.put("excludedTables", JSONArray(EXCLUDED_TABLES))
        root.put("rowCount", snapshot.rowCount())

        snapshot.user?.let { u ->
            root.put("user", JSONObject().apply {
                put("id", u.id)
                put("phoneNumber", u.phoneNumber)
                put("token", u.token)
                put("businessName", u.businessName)
                put("gstin", u.gstin)
                put("firstName", u.firstName)
                put("middleName", u.middleName)
                put("surname", u.surname)
                put("fatherName", u.fatherName)
                put("dob", u.dob)
                put("dod", u.dod)
                put("ownerName", u.ownerName)
                put("email", u.email)
                put("address", u.address)
                put("city", u.city)
                put("state", u.state)
                put("pincode", u.pincode)
                put("gstRegistrationDate", u.gstRegistrationDate)
                put("gstStatus", u.gstStatus)
                put("constitutionOfBusiness", u.constitutionOfBusiness)
                put("upiId", u.upiId)
                put("previousFyAggregateTurnover", u.previousFyAggregateTurnover)
                put("filingScheme", u.filingScheme)
                put("businessType", u.businessType.name)
                put("enableInventory", u.enableInventory)
                put("isLoggedIn", u.isLoggedIn)
            })
        }

        root.put("ledgerGroups", JSONArray().apply {
            snapshot.ledgerGroups.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("category", g.category.name)
                    put("parentGroupId", g.parentGroupId ?: JSONObject.NULL)
                })
            }
        })

        root.put("ledgers", JSONArray().apply {
            snapshot.ledgers.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("groupId", l.groupId)
                    put("groupName", l.groupName)
                    put("category", l.category.name)
                    put("openingBalance", l.openingBalance)
                    put("balanceType", l.balanceType.name)
                    put("currentBalance", l.currentBalance)
                    put("pincode", l.pincode)
                    put("city", l.city)
                    put("state", l.state)
                    put("country", l.country)
                    put("gstin", l.gstin)
                    // Without this a restore would drop the CASH/BANK identity, and the
                    // posting engine would create a fresh duplicate cash ledger — exactly
                    // the split C2 exists to fix.
                    put("systemCode", l.systemCode ?: JSONObject.NULL)
                })
            }
        })

        root.put("inventoryItems", JSONArray().apply {
            snapshot.inventoryItems.forEach { i ->
                put(JSONObject().apply {
                    put("id", i.id)
                    put("name", i.name)
                    put("unit", i.unit)
                    put("hsnCode", i.hsnCode)
                    put("gstRate", i.gstRate)
                    put("stockQty", i.stockQty)
                    put("avgCostPrice", i.avgCostPrice)
                    put("sellingPrice", i.sellingPrice)
                })
            }
        })

        root.put("vouchers", JSONArray().apply {
            snapshot.vouchers.forEach { v ->
                put(JSONObject().apply {
                    put("id", v.id)
                    put("voucherNo", v.voucherNo)
                    put("voucherType", v.voucherType.name)
                    put("date", v.date)
                    put("partyName", v.partyName)
                    put("totalAmount", v.totalAmount)
                    put("gstAmount", v.gstAmount)
                    put("isInterstate", v.isInterstate)
                    put("narration", v.narration)
                    put("isSynced", v.isSynced)
                    put("tags", v.tags)
                    put("paymentMode", v.paymentMode)
                })
            }
        })

        root.put("journalEntries", JSONArray().apply {
            snapshot.journalEntries.forEach { j ->
                put(JSONObject().apply {
                    put("id", j.id)
                    put("voucherId", j.voucherId)
                    put("ledgerId", j.ledgerId)
                    put("debitAmount", j.debitAmount)
                    put("creditAmount", j.creditAmount)
                })
            }
        })

        root.put("voucherItems", JSONArray().apply {
            snapshot.voucherItems.forEach { vi ->
                put(JSONObject().apply {
                    put("id", vi.id)
                    put("voucherId", vi.voucherId)
                    put("itemId", vi.itemId)
                    put("quantity", vi.quantity)
                    put("rate", vi.rate)
                    put("costRate", vi.costRate)
                    put("amount", vi.amount)
                    put("gstRate", vi.gstRate)
                    put("cgstAmount", vi.cgstAmount)
                    put("sgstAmount", vi.sgstAmount)
                    put("igstAmount", vi.igstAmount)
                })
            }
        })

        root.put("gstTaxDetails", JSONArray().apply {
            snapshot.gstTaxDetails.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id)
                    put("voucherId", g.voucherId)
                    put("isInterstate", g.isInterstate)
                    put("taxableValue", g.taxableValue)
                    put("cgstRate", g.cgstRate)
                    put("sgstRate", g.sgstRate)
                    put("igstRate", g.igstRate)
                    put("cgstAmount", g.cgstAmount)
                    put("sgstAmount", g.sgstAmount)
                    put("igstAmount", g.igstAmount)
                })
            }
        })

        root.put("voucherConfigs", JSONArray().apply {
            snapshot.voucherConfigs.forEach { c ->
                put(JSONObject().apply {
                    put("voucherType", c.voucherType)
                    put("prefix", c.prefix)
                    put("nextNumber", c.nextNumber)
                    put("autoIncrement", c.autoIncrement)
                    put("description", c.description)
                })
            }
        })

        root.put("reconciliations", JSONArray().apply {
            snapshot.reconciliations.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("partyName", r.partyName)
                    put("invoiceVoucherNo", r.invoiceVoucherNo)
                    put("invoiceVoucherId", r.invoiceVoucherId ?: JSONObject.NULL)
                    put("voucherType", r.voucherType.name)
                    put("expectedAmount", r.expectedAmount)
                    put("receivedAmount", r.receivedAmount)
                    put("discrepancyAmount", r.discrepancyAmount)
                    put("status", r.status.name)
                    put("discrepancyReason", r.discrepancyReason)
                    put("detectedAt", r.detectedAt)
                    put("isResolved", r.isResolved)
                    put("resolutionNotes", r.resolutionNotes)
                })
            }
        })

        return root.toString(2)
    }

    // ---------------- read ----------------

    class IncompatibleBackupException(message: String) : Exception(message)

    /**
     * Parses a backup file. Throws [IncompatibleBackupException] rather than importing a
     * partial set of books — a restore that silently drops what it cannot read is how a
     * user ends up with a plausible-looking but incomplete ledger.
     */
    fun fromJson(jsonStr: String): BookSnapshot {
        val root = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            throw IncompatibleBackupException("Not a valid backup file: ${e.message}")
        }

        val format = root.optString("format")
        if (format != FORMAT) {
            throw IncompatibleBackupException(
                if (root.has("app")) {
                    "This is an older backup written before full-fidelity backups existed. " +
                        "It cannot reproduce voucher numbers, dates or ledger groups, so it is " +
                        "refused rather than imported as an incomplete set of books."
                } else {
                    "Unrecognised file — expected a $FORMAT backup."
                }
            )
        }

        val version = root.optInt("formatVersion", -1)
        if (version > FORMAT_VERSION) {
            throw IncompatibleBackupException(
                "This backup was written by a newer version of the app (format $version, " +
                    "this build reads up to $FORMAT_VERSION). Update the app to restore it."
            )
        }

        val user = if (root.has("user")) {
            val u = root.getJSONObject("user")
            UserEntity(
                id = u.optString("id", "primary_user"),
                phoneNumber = u.optString("phoneNumber", ""),
                token = u.optString("token", ""),
                businessName = u.optString("businessName", ""),
                gstin = u.optString("gstin", ""),
                firstName = u.optString("firstName", ""),
                middleName = u.optString("middleName", ""),
                surname = u.optString("surname", ""),
                fatherName = u.optString("fatherName", ""),
                dob = u.optString("dob", ""),
                dod = u.optString("dod", ""),
                ownerName = u.optString("ownerName", ""),
                email = u.optString("email", ""),
                address = u.optString("address", ""),
                city = u.optString("city", ""),
                state = u.optString("state", ""),
                pincode = u.optString("pincode", ""),
                gstRegistrationDate = u.optString("gstRegistrationDate", ""),
                gstStatus = u.optString("gstStatus", ""),
                constitutionOfBusiness = u.optString("constitutionOfBusiness", ""),
                upiId = u.optString("upiId", ""),
                previousFyAggregateTurnover = u.optDouble("previousFyAggregateTurnover", -1.0),
                filingScheme = u.optString("filingScheme", "MONTHLY"),
                businessType = enumOrDefault(u.optString("businessType"), BusinessType.TRADING),
                enableInventory = u.optBoolean("enableInventory", true),
                isLoggedIn = u.optBoolean("isLoggedIn", true)
            )
        } else null

        return BookSnapshot(
            user = user,
            ledgerGroups = root.array("ledgerGroups") { o ->
                LedgerGroupEntity(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    category = enumOrDefault(o.optString("category"), LedgerCategory.EXPENSE),
                    parentGroupId = if (o.isNull("parentGroupId")) null else o.getLong("parentGroupId")
                )
            },
            ledgers = root.array("ledgers") { o ->
                LedgerEntity(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    groupId = o.getLong("groupId"),
                    groupName = o.optString("groupName", "General Ledgers"),
                    category = enumOrDefault(o.optString("category"), LedgerCategory.EXPENSE),
                    openingBalance = o.optDouble("openingBalance", 0.0),
                    balanceType = enumOrDefault(o.optString("balanceType"), BalanceType.DR),
                    currentBalance = o.optDouble("currentBalance", 0.0),
                    pincode = o.optString("pincode", ""),
                    city = o.optString("city", ""),
                    state = o.optString("state", ""),
                    country = o.optString("country", "India"),
                    gstin = o.optString("gstin", ""),
                    // Absent in backups written before ledger identity existed; the
                    // resolver then adopts by name, so an older backup still restores.
                    systemCode = if (o.isNull("systemCode")) null else o.optString("systemCode").ifBlank { null }
                )
            },
            inventoryItems = root.array("inventoryItems") { o ->
                InventoryItemEntity(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    unit = o.optString("unit", "Pcs"),
                    hsnCode = o.optString("hsnCode", ""),
                    gstRate = o.optDouble("gstRate", 18.0),
                    stockQty = o.optDouble("stockQty", 0.0),
                    avgCostPrice = o.optDouble("avgCostPrice", 0.0),
                    sellingPrice = o.optDouble("sellingPrice", 0.0)
                )
            },
            vouchers = root.array("vouchers") { o ->
                VoucherEntity(
                    id = o.getLong("id"),
                    voucherNo = o.getString("voucherNo"),
                    voucherType = enumOrDefault(o.optString("voucherType"), VoucherType.JOURNAL),
                    date = o.getLong("date"),
                    partyName = o.optString("partyName", ""),
                    totalAmount = o.optDouble("totalAmount", 0.0),
                    gstAmount = o.optDouble("gstAmount", 0.0),
                    isInterstate = o.optBoolean("isInterstate", false),
                    narration = o.optString("narration", ""),
                    isSynced = o.optBoolean("isSynced", false),
                    tags = o.optString("tags", ""),
                    paymentMode = o.optString("paymentMode", "CASH")
                )
            },
            journalEntries = root.array("journalEntries") { o ->
                JournalEntryEntity(
                    id = o.getLong("id"),
                    voucherId = o.getLong("voucherId"),
                    ledgerId = o.getLong("ledgerId"),
                    debitAmount = o.optDouble("debitAmount", 0.0),
                    creditAmount = o.optDouble("creditAmount", 0.0)
                )
            },
            voucherItems = root.array("voucherItems") { o ->
                VoucherItemEntity(
                    id = o.getLong("id"),
                    voucherId = o.getLong("voucherId"),
                    itemId = o.getLong("itemId"),
                    quantity = o.optDouble("quantity", 0.0),
                    rate = o.optDouble("rate", 0.0),
                    costRate = o.optDouble("costRate", 0.0),
                    amount = o.optDouble("amount", 0.0),
                    gstRate = o.optDouble("gstRate", 0.0),
                    cgstAmount = o.optDouble("cgstAmount", 0.0),
                    sgstAmount = o.optDouble("sgstAmount", 0.0),
                    igstAmount = o.optDouble("igstAmount", 0.0)
                )
            },
            gstTaxDetails = root.array("gstTaxDetails") { o ->
                GstTaxDetailEntity(
                    id = o.getLong("id"),
                    voucherId = o.getLong("voucherId"),
                    isInterstate = o.optBoolean("isInterstate", false),
                    taxableValue = o.optDouble("taxableValue", 0.0),
                    cgstRate = o.optDouble("cgstRate", 0.0),
                    sgstRate = o.optDouble("sgstRate", 0.0),
                    igstRate = o.optDouble("igstRate", 0.0),
                    cgstAmount = o.optDouble("cgstAmount", 0.0),
                    sgstAmount = o.optDouble("sgstAmount", 0.0),
                    igstAmount = o.optDouble("igstAmount", 0.0)
                )
            },
            voucherConfigs = root.array("voucherConfigs") { o ->
                VoucherTypeConfigEntity(
                    voucherType = o.getString("voucherType"),
                    prefix = o.optString("prefix", ""),
                    nextNumber = o.optLong("nextNumber", 1001L),
                    autoIncrement = o.optBoolean("autoIncrement", true),
                    description = o.optString("description", "")
                )
            },
            reconciliations = root.array("reconciliations") { o ->
                ReconciliationDiscrepancyEntity(
                    id = o.getLong("id"),
                    partyName = o.optString("partyName", ""),
                    invoiceVoucherNo = o.optString("invoiceVoucherNo", ""),
                    invoiceVoucherId = if (o.isNull("invoiceVoucherId")) null else o.getLong("invoiceVoucherId"),
                    voucherType = enumOrDefault(o.optString("voucherType"), VoucherType.SALES),
                    expectedAmount = o.optDouble("expectedAmount", 0.0),
                    receivedAmount = o.optDouble("receivedAmount", 0.0),
                    discrepancyAmount = o.optDouble("discrepancyAmount", 0.0),
                    status = enumOrDefault(o.optString("status"), ReconciliationStatus.SHORTFALL),
                    discrepancyReason = o.optString("discrepancyReason", ""),
                    detectedAt = o.optLong("detectedAt", 0L),
                    isResolved = o.optBoolean("isResolved", false),
                    resolutionNotes = o.optString("resolutionNotes", "")
                )
            }
        )
    }

    private inline fun <T> JSONObject.array(key: String, build: (JSONObject) -> T): List<T> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { build(arr.getJSONObject(it)) }
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(raw: String?, fallback: E): E =
        if (raw.isNullOrBlank()) fallback
        else runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
}
