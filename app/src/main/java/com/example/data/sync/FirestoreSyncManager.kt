package com.example.data.sync

import android.util.Log
import com.example.data.model.BalanceType
import com.example.data.model.InventoryItemEntity
import com.example.data.model.LedgerEntity
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CloudBackupPayload(
    val user: UserEntity?,
    val vouchers: List<VoucherEntity>,
    val ledgers: List<LedgerEntity>,
    val inventory: List<InventoryItemEntity>
)

class FirestoreSyncManager {

    private val db: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Error getting Firestore instance", e)
            null
        }
    }

    suspend fun syncToCloud(
        user: UserEntity,
        vouchers: List<VoucherEntity>,
        ledgers: List<LedgerEntity>,
        inventory: List<InventoryItemEntity>
    ): Result<String> {
        return try {
            val firestore = db ?: return Result.failure(Exception("Firestore is unavailable"))
            val userDocRef = firestore.collection("accounting_users").document(user.phoneNumber)

            // 1. Sync User Profile
            val userMap = mapOf(
                "id" to user.id,
                "businessName" to user.businessName,
                "firstName" to user.firstName,
                "middleName" to user.middleName,
                "surname" to user.surname,
                "ownerName" to user.ownerName,
                "phoneNumber" to user.phoneNumber,
                "email" to user.email,
                "gstin" to user.gstin,
                "gstStatus" to user.gstStatus,
                "address" to user.address,
                "pincode" to user.pincode,
                "city" to user.city,
                "state" to user.state,
                "businessType" to user.businessType.name,
                "enableInventory" to user.enableInventory,
                "lastSyncedAt" to SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            )
            userDocRef.set(userMap).await()

            // 2. Sync Vouchers
            val vouchersCol = userDocRef.collection("vouchers")
            vouchers.forEach { v ->
                val vMap = mapOf(
                    "id" to v.id,
                    "voucherNo" to v.voucherNo,
                    "voucherType" to v.voucherType.name,
                    "date" to v.date,
                    "partyName" to v.partyName,
                    "totalAmount" to v.totalAmount,
                    "gstAmount" to v.gstAmount,
                    "isInterstate" to v.isInterstate,
                    "narration" to v.narration,
                    "isSynced" to v.isSynced
                )
                vouchersCol.document(v.id.toString()).set(vMap).await()
            }

            // 3. Sync Ledgers
            val ledgersCol = userDocRef.collection("ledgers")
            ledgers.forEach { l ->
                val lMap = mapOf(
                    "id" to l.id,
                    "name" to l.name,
                    "groupId" to l.groupId,
                    "openingBalance" to l.openingBalance,
                    "balanceType" to l.balanceType.name,
                    "currentBalance" to l.currentBalance,
                    "pincode" to l.pincode,
                    "city" to l.city,
                    "state" to l.state,
                    "country" to l.country,
                    "gstin" to l.gstin
                )
                ledgersCol.document(l.id.toString()).set(lMap).await()
            }

            // 4. Sync Inventory
            val invCol = userDocRef.collection("inventory")
            inventory.forEach { i ->
                val iMap = mapOf(
                    "id" to i.id,
                    "name" to i.name,
                    "unit" to i.unit,
                    "hsnCode" to i.hsnCode,
                    "gstRate" to i.gstRate,
                    "stockQty" to i.stockQty,
                    "avgCostPrice" to i.avgCostPrice,
                    "sellingPrice" to i.sellingPrice
                )
                invCol.document(i.id.toString()).set(iMap).await()
            }

            val syncTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            Result.success("Cloud sync successful! Saved ${vouchers.size} vouchers & ${ledgers.size} ledgers at $syncTime")
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Sync failed", e)
            Result.failure(e)
        }
    }

    suspend fun restoreFromCloud(phoneNumber: String): Result<CloudBackupPayload> {
        return try {
            val firestore = db ?: return Result.failure(Exception("Firestore is unavailable"))
            val userDocRef = firestore.collection("accounting_users").document(phoneNumber)

            val userSnap = userDocRef.get().await()
            if (!userSnap.exists()) {
                return Result.failure(Exception("No cloud record found for phone $phoneNumber"))
            }

            // Vouchers
            val vouchersSnap = userDocRef.collection("vouchers").get().await()
            val restoredVouchers = vouchersSnap.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val vNo = doc.getString("voucherNo") ?: ""
                val vTypeStr = doc.getString("voucherType") ?: "SALES"
                val vType = try { VoucherType.valueOf(vTypeStr) } catch (e: Exception) { VoucherType.SALES }
                val date = doc.getLong("date") ?: System.currentTimeMillis()
                val party = doc.getString("partyName") ?: ""
                val total = doc.getDouble("totalAmount") ?: 0.0
                val gstAmount = doc.getDouble("gstAmount") ?: 0.0
                val isInterstate = doc.getBoolean("isInterstate") ?: false
                val narration = doc.getString("narration") ?: ""

                VoucherEntity(
                    id = id,
                    voucherNo = vNo,
                    voucherType = vType,
                    date = date,
                    partyName = party,
                    totalAmount = total,
                    gstAmount = gstAmount,
                    isInterstate = isInterstate,
                    narration = narration,
                    isSynced = true
                )
            }

            // Ledgers
            val ledgersSnap = userDocRef.collection("ledgers").get().await()
            val restoredLedgers = ledgersSnap.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val name = doc.getString("name") ?: ""
                val groupId = doc.getLong("groupId") ?: 1L
                val opBal = doc.getDouble("openingBalance") ?: 0.0
                val bTypeStr = doc.getString("balanceType") ?: "DR"
                val bType = try { BalanceType.valueOf(bTypeStr) } catch (e: Exception) { BalanceType.DR }
                val curBal = doc.getDouble("currentBalance") ?: 0.0
                val pincode = doc.getString("pincode") ?: ""
                val city = doc.getString("city") ?: ""
                val state = doc.getString("state") ?: ""
                val country = doc.getString("country") ?: "India"
                val gstin = doc.getString("gstin") ?: ""

                LedgerEntity(
                    id = id,
                    name = name,
                    groupId = groupId,
                    openingBalance = opBal,
                    balanceType = bType,
                    currentBalance = curBal,
                    pincode = pincode,
                    city = city,
                    state = state,
                    country = country,
                    gstin = gstin
                )
            }

            // Inventory
            val invSnap = userDocRef.collection("inventory").get().await()
            val restoredInv = invSnap.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val name = doc.getString("name") ?: ""
                val unit = doc.getString("unit") ?: "Pcs"
                val hsn = doc.getString("hsnCode") ?: ""
                val rate = doc.getDouble("gstRate") ?: 18.0
                val stockQty = doc.getDouble("stockQty") ?: 0.0
                val avgCostPrice = doc.getDouble("avgCostPrice") ?: 0.0
                val price = doc.getDouble("sellingPrice") ?: 0.0

                InventoryItemEntity(
                    id = id,
                    name = name,
                    unit = unit,
                    hsnCode = hsn,
                    gstRate = rate,
                    stockQty = stockQty,
                    avgCostPrice = avgCostPrice,
                    sellingPrice = price
                )
            }

            Result.success(CloudBackupPayload(null, restoredVouchers, restoredLedgers, restoredInv))
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Restore failed", e)
            Result.failure(e)
        }
    }

    // ---- Full-fidelity cloud snapshot ----
    //
    // The per-field maps above carry 4 entity types and cannot reproduce a set of
    // books: no ledger groups, no journal entries, no GST detail rows, no voucher
    // numbering state. Restoring from them meant re-posting vouchers, which reissued
    // numbers and guessed tax rates. This stores the same full-fidelity JSON the
    // on-device backup writes, as a single document, so a cloud restore and a local
    // restore produce identical books.

    /** Firestore's hard per-document ceiling is 1 MiB; stay clear of it. */
    private val maxSnapshotBytes = 900_000

    suspend fun uploadBooksSnapshot(phoneNumber: String, json: String): Result<String> {
        return try {
            val firestore = db ?: return Result.failure(Exception("Firestore is unavailable"))
            val bytes = json.toByteArray(Charsets.UTF_8).size
            if (bytes > maxSnapshotBytes) {
                return Result.failure(
                    Exception(
                        "Books are too large for a single cloud backup ($bytes bytes, limit " +
                            "$maxSnapshotBytes). Use an on-device backup and copy the file off " +
                            "the phone instead."
                    )
                )
            }
            firestore.collection("accounting_users")
                .document(phoneNumber)
                .collection("book_snapshots")
                .document("latest")
                .set(
                    mapOf(
                        "json" to json,
                        "sizeBytes" to bytes,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                .await()
            Result.success("Cloud snapshot updated ($bytes bytes)")
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Snapshot upload failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadBooksSnapshot(phoneNumber: String): Result<String> {
        return try {
            val firestore = db ?: return Result.failure(Exception("Firestore is unavailable"))
            val snap = firestore.collection("accounting_users")
                .document(phoneNumber)
                .collection("book_snapshots")
                .document("latest")
                .get()
                .await()

            val json = snap.getString("json")
            if (!snap.exists() || json.isNullOrBlank()) {
                Result.failure(
                    Exception(
                        "No full cloud backup found for this account. Press Sync Books first — " +
                            "older cloud data cannot reproduce voucher numbers, dates or ledger " +
                            "groups, so it is not restored."
                    )
                )
            } else {
                Result.success(json)
            }
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Snapshot download failed", e)
            Result.failure(e)
        }
    }
}
