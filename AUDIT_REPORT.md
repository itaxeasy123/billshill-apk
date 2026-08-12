# Bill-Shill — Full Application Audit

**App:** `bill-shill` — native Android (Kotlin + Jetpack Compose + Room), Indian double-entry accounting / GST
**Audited:** 12 August 2026
**Method:** 7 parallel specialist agents (one per subsystem), then independent re-verification of every headline claim by direct code inspection, live emulator reproduction, and SQL executed against the app's own database. Statutory claims checked against gst.gov.in / CBIC / Tally documentation.
**Coverage:** 5 screens, 11 report tabs, 7 dashboard tabs, 23 UI components, 69 Kotlin files (~21.5k lines) — the entire `com.example` source tree.

> **Legend** — **CONFIRMED**: traced in code, and in most cases reproduced on a running emulator or by executing the SQL. **SUSPECTED**: strongly indicated by the code but not exercised at runtime.

---

## Verdict

The **voucher-posting arithmetic is sound**. All 8 voucher types produce exactly balanced journals — debits equal credits in every branch, including zero-GST, inter-state, and inventory-attached paths. The Trial Balance is genuine, correctly-computed SQL. That core is worth keeping.

Almost everything built on top of it fails, in four distinct ways:

1. **Balanced, but posted to the wrong accounts.** Journals always balance — that is not the same as being right. A manual voucher ignores the credit account the user picked. Every Receipt/Payment/Contra writes to a cash ledger that *does not exist*, silently creating a second one. Purchases from a vendor whose name contains "car" are booked as fixed assets.
2. **Fabricated data shown as the user's own books.** The Balance Sheet and P&L are hardcoded literals belonging to a different, real business. Several GST screens invent their numbers outright.
3. **No working backup or restore.** Four "backup" surfaces exist; none will return a user's books. One fakes success outright.
4. **Features that claim to do what they don't.** A QR scanner with no camera, a UPI QR that cannot be scanned, a contacts picker with no contacts access, "AI" that is broken regex.

**61 technical findings — 12 Critical · 26 High · 17 Medium · 6 Low — plus 16 honest-labelling issues. 76 in total.**
*(H1 is listed under HIGH for traceability but carries a Medium verdict after verification, so by severity the split is 25 High / 18 Medium.)*

> **This document is post-verification and self-contained.** Every original finding was re-tested by a second wave of 7 agents, each briefed to *disprove* it and none seeing the others' work. **0 of 61 were INVALID.** 4 were narrowed (marked `VERIFIED → OVERSTATED` inline), 1 escalated, and **14 new issues were found — those are folded in below, tagged `[V]`.** Full verdict-by-verdict record in `VERIFICATION_REPORT.md`.
>
> Note: **H1 remains in the HIGH section for traceability but carries a Medium verdict** after verification.

---

## CRITICAL

### C1. Balance Sheet & P&L show another company's books — hardcoded
`ui/components/HierarchicalFinancialStatement.kt:195-203, 318-327`

Both statements are literal constants. `trialBalance` is passed in and **never used for any total** — only to filter a drill-down popup.

```kotlin
val capitalAccountTotal = 7039903.30
val totalAssets        = 33798194.07
val salesTotal         = 32966569.99
val netProfit          = 7326645.11
```

The fallback identity is a real business: `"Batra Sons -25-26"`, GSTIN `23ADOPS9429A1ZE`, address `"Sarafa Bazar, Lashkar, Gwalior"` — and unlike the name and GSTIN, the address has **no `.ifBlank` guard at all**, so it prints for every business even when their own details are correctly configured.

**Reproduced on-device.** With exactly one ₹10,000 sale in the ledger, the P&L reports **Sales ₹3,29,66,569.99** and stock ₹50,50,310.00. The Balance Sheet — captioned *"Real-Time Balance Sheet"* — reports Current Assets ₹2,24,23,221.41. Both print a hardcoded period `1-Apr-2025 to 31-Mar-2026`, ignoring the FY 2026-27 selector directly above them.

There is therefore **no working Balance Sheet or P&L in this app**. Re-wiring them is necessary but not sufficient — closing stock is never posted to any ledger (H14), so gross profit would still be wrong.

> A third party's real GSTIN and trading address are compiled into the shipped APK. Worth resolving on its own terms, separately from the accounting defect.

### C2. Every Receipt, Payment and Contra writes to a cash ledger that doesn't exist
`service/DatabaseSeedEngine.kt:37,59` vs `data/repository/AccountingRepository.kt:496,509,521`

Three spellings, none matching:

| Where | Name |
|---|---|
| Seeded ledger | `"Cash in Hand"` |
| Seeded group | `"Cash-in-Hand"` |
| What the code looks up | `"Cash-in-hand"` |

Lookups use exact-match `WHERE name = :name` under SQLite's default case-sensitive BINARY collation.

**Proven against the live database:**
```
ledgers LIKE '%ash%'            →  1 | Cash in Hand | Cash-in-Hand | 50000.0
WHERE name = 'Cash-in-hand'     →  0 matches
```

So the first Receipt/Payment/Contra creates a **second, empty cash ledger** under a **second, new group**. The ₹50,000 opening balance stays stranded on the original; all cash movement lands on the duplicate. A user opening "Cash in Hand" will never see the transactions that moved their cash. Aggregate widgets using `LIKE '%Cash%'` still total correctly, which is exactly what hides this. (Bank is unaffected — `"HDFC Bank Ltd"` matches.)

### C3. The Manual Voucher form ignores the credit account the user selected
`ui/components/ManualVoucherForm.kt:274` → `ui/AccountingViewModel.kt:418-447`

The form offers a Debit Account and a Credit Account picker and shows a green "BALANCED: DR = CR" confirmation. Verified: `creditLedgerName` reaches the ViewModel and then appears **only inside the narration string** — it is never passed to the repository. `debitLedgerName` is re-purposed as `partyName`, and `createVoucher`'s hardcoded per-type logic takes over:

- **JOURNAL** → always `Dr General Expenses / Cr <the account you chose to debit>`. Choosing Dr Depreciation ₹15,000 / Cr Accumulated Depreciation actually posts **Dr General Expenses ₹15,000 / Cr Depreciation ₹15,000** — the intended expense is *credited*, and the account you chose to credit is never touched.
- **RECEIPT/PAYMENT** → the party is looked up from the *debit* name. Choosing Dr HDFC Bank / Cr Ramesh Kumar posts **Dr HDFC Bank / Cr HDFC Bank** — a self-cancelling wash entry. The customer's receivable never moves.
- **CONTRA** → ignores both selections entirely.

A correct generic two-ledger poster already exists (`createCustomVoucher`, `AccountingRepository.kt:210-240`). This screen simply calls the wrong method.

### C4. "Backup Now" backs up nothing and reports success
`ui/AccountingViewModel.kt:457-463` → `ui/screens/SettingsScreen.kt:1005-1013`

```kotlin
fun triggerManualCloudBackup() {
    viewModelScope.launch {
        userSettingsDataStore.updateLastBackupTime(nowStr)
        _messageEvent.emit("Room Database & Ledger entries backed up to Cloud successfully ($nowStr)!")
    }
}
```

No file write. No Firestore call. No worker enqueued. It updates a timestamp label and toasts success; Settings then shows "Last Backup Status" in green. A user backing up before switching phones is told it worked. It did not.

### C5. JSON backup omits the entire double-entry ledger
`data/repository/AccountingRepository.kt:662-733`

Verified — the export writes exactly six keys: `app`, `timestamp`, `user`, `vouchers`, `ledgers`, `inventory`. **There is no `journalEntries` key.** Of **13** Room entities only 4 are exported; **9 are absent entirely**: `LedgerGroupEntity`, `JournalEntryEntity`, `VoucherItemEntity`, `GstTaxDetailEntity`, `SyncLogEntity`, `VoucherTypeConfigEntity`, `ReconciliationDiscrepancyEntity`, `CrashLog`, `MonthlyArchive`.

Import re-derives journals by replaying `createVoucher()`, which cannot restore the original:
- `createVoucher()` has no `date` parameter — it hardcodes `System.currentTimeMillis()`. **Every restored voucher is stamped with today's date.**
- Voucher numbers are regenerated, so they no longer match invoices already issued.
- Ledger `openingBalance`, `balanceType`, `gstin`, `city`, `state` are written to the file but never read back; import uses only `name`, then force-creates anything missing as `Sundry Debtors / ASSET`. A Sundry Creditors liability flips to the asset side.

### C6. Import never clears existing data — restoring doubles the books
`data/repository/AccountingRepository.kt:735-806`

No delete or clear anywhere. Vouchers and inventory insert unconditionally with no existence check. Restoring into a device that already holds the data — the normal case — duplicates every voucher and journal entry, doubling every balance, the Trial Balance and the P&L. A user could file GST on doubled figures.

### C7. Firestore restore re-posts at the wrong GST rate and discards most of the payload
`ui/AccountingViewModel.kt:198-229`

`restoreDataFromFirestore()` calls `createVoucher(...)` **without passing `gstRate`**, so the `18.0` default applies to everything. A 5%, 12%, 0% or 28% invoice returns as 18% — wrong CGST/SGST/IGST split, wrong Trial Balance. `payload.ledgers` and `payload.inventory` are fetched over the network and never read. Same duplication as C6.

### C8. Exclusive GST is never added to the invoice — every invoice under-charges tax
`ui/screens/VouchersScreen.kt:172-187, 679-683, 705-717` → `AccountingRepository.kt:257-267`

Two stacked defects on the app's **default** entry path ("Exclusive (+ Tax)" is selected by default). The correct grossed-up total is computed, then discarded:

```kotlin
val finalTotalAmount = if (isGstInclusive) amount else (amount + gstAmount)  // computed…
MonetaryRow(label = "Total Balanced Voucher Value", amount = amount, ...)     // …never used
```

Save forwards the raw entered string. `addVoucher` has **no inclusive/exclusive parameter at all**, and `createVoucher` unconditionally treats the figure as GST-inclusive.

**₹10,000 base @ 18%, intra-state:**

| | Correct | App produces |
|---|---|---|
| Taxable value | 10,000.00 | 8,474.58 |
| CGST | 900.00 | 762.71 |
| SGST | 900.00 | 762.71 |
| **Invoice total** | **11,800.00** | **10,000.00** |

**₹274.58 of GST under-collected on one invoice — a 15.25% short-payment**; revenue understated by ₹1,525.42. Reproduced on the emulator: Review & Post displayed "Taxable ₹10,000 / CGST ₹900 / SGST ₹900" and then posted a ₹10,000 voucher.

### C9. No negative-stock guard anywhere
`data/dao/AccountingDao.kt:144-145` — `UPDATE inventory_items SET stockQty = stockQty + :qtyDelta`

Pure additive SQL, no clamp, no pre-check, no quantity validation. A repo-wide search for any guard (`stockQty <`, "Low Stock", "insufficient") returns **zero matches**. Selling 50 units of an item with 5 in stock posts normally; Stock Status renders `-45 Pcs` with no warning.

### C10. GST return exports are fabricated and unfileable
`data/gst/GstAutomationEngine.kt:139,166,177,284` · `ui/screens/ReportsScreen.kt:751-763, 797-801`

- `rt = 18.0` **hardcoded for every invoice line** — the real rate exists in `GstTaxDetailEntity` and is ignored. Every 5%/12%/28% sale is misreported at 18%.
- `val hsnCode = "9983"` — all turnover bucketed under one invented HSN, though real per-item HSN codes exist and display correctly elsewhere.
- The "GST Rate Summary" tile assumes a fixed **10/20/60/10%** slab split for every business, always.
- The "HSN/SAC Summary" invents three codes at 60/25/15% — a grocery shop is shown *"HSN 8471 Computers & IT Hardware"*.
- ITC type is `ty = "ALL_OTHER_ITC"`; the valid GSTN enum is `IMPG|IMPS|ISRC|ISD|OTH`. Correct value is `"OTH"`.

The payload is wrapped in an invented envelope the GST portal's offline utility does not accept — despite the file's own comment promising a "government-compliant" payload.

### C11. Restoring a backup silently zeroes all inventory stock `[V]`
`data/repository/AccountingRepository.kt:644-656, 787-800`

`exportDataToJson` writes `stockQty`, but the import loop never reads it and `createInventoryItem` hardcodes it away:

```kotlin
InventoryItemEntity(name = name, ..., stockQty = 0.0, avgCostPrice = cost, sellingPrice = price)
```

Combined with C6 (no clear-first), restoring a backup both **duplicates every inventory item and resets all stock to zero**. Silent, unrecoverable, and distinct from the journal-entry loss in C5.

### C12. `fallbackToDestructiveMigration()` — any schema bump silently wipes the user's books `[V]`
`data/db/AppDatabase.kt:104-105`
> ✅ **FIXED** — replaced with `fallbackToDestructiveMigrationOnDowngrade()`. Verified compiling.

Found while designing the remediation, not during the original audit. The database registered only `MIGRATION_6_7` and `MIGRATION_7_8` at `version = 8`, then enabled destructive fallback. **Any future version bump without an exactly-matching `Migration` would drop every table and recreate it — no warning, no backup, total loss of the user's books.**

This was the single most dangerous line in the codebase precisely *because* the remediation plan requires new migrations. A mistake in `MIGRATION_8_9` would have destroyed data silently instead of failing loudly. Failing loudly is strictly safer for an accounting app; downgrades remain destructive because there is no forward-compatible way to read a newer schema with older entity definitions.

---

## HIGH

### H1. One shared CGST/SGST/IGST ledger nets output tax against input credit
> **VERIFIED → OVERSTATED, downgraded to Medium.** GST *filing* totals are computed independently from `gst_tax_details` by voucher type, so returns are unaffected. Impact is confined to ledger-register presentation.
`service/DatabaseSeedEngine.kt:65-68` · `AccountingRepository.kt:321-327, 424-430`

Sales *credit* and purchases *debit* the **same** `"CGST"` / `"SGST"` / `"IGST"` ledgers. The balance is therefore a net figure, not the gross Output Tax Payable and gross ITC that GSTR-3B requires as separate accounts. Standard practice (and Tally's default) is distinct Output and Input ledgers. Filing totals themselves are safe — `getGstSummaryFlow` recomputes both sides from `gst_tax_details` — but the ledger register is misleading and non-compliant.

### H2. Purchase "asset detection" matches the vendor's name, not what was bought
> **VERIFIED → worse than written.** Debiting the vendor's own payable ledger is *structurally guaranteed by call order*, not conditional on the vendor pre-existing.
`data/repository/AccountingRepository.kt:392-411`

```kotlin
val isAssetPurchase = partyName.lowercase().contains("car") || ... contains("computer") ...
```

Verified. Buying ₹1,000 of stationery from **"Carlson Traders"** trips `contains("car")` and books it as a Fixed Asset. Compounding problems: the asset ledger is named after the *vendor* ("Carlson Traders", not "Computers"); all asset-purchase payables collapse into one shared ledger literally named `"Sundry Creditors / Cash"`; and if that vendor already exists as a creditor, the code **debits the vendor's own payable ledger as if it were a fixed asset**, silently destroying their balance.

### H3. Receipt/Payment infer cash-vs-bank from a substring of the party's name
`data/repository/AccountingRepository.kt:495-499, 508-512`

There is no payment-mode field; the code tests `partyName.lowercase().contains("cash")`. A customer named **"Cashmere Textiles Pvt Ltd"** paying ₹25,000 by NEFT posts **Dr Cash-in-hand ₹25,000**. The books show cash in the drawer that was never received, and the bank will never reconcile.

### H4. New vendors on a Purchase Return are classified as debtors
`data/repository/AccountingRepository.kt:558-577`

`getOrCreatePartyLedger` enumerates only SALES/RECEIPT and PURCHASE/PAYMENT; `SALES_RETURN`, `PURCHASE_RETURN`, `JOURNAL`, `CONTRA` fall through to `else -> "Sundry Debtors"`. A purchase return against a not-yet-existing vendor creates them as a **Sundry Debtor (Asset)** instead of a creditor — overstating assets. The misclassification is permanent: the function only ever creates, never re-classifies.

### H5. `updateVoucher` changes the date, burns a new number, and drops inventory
`data/repository/AccountingRepository.kt:624-642`

It is `deleteVoucher` + `createVoucher` with **no date, item, quantity or tag parameters**. Consequences: the transaction date silently moves to *now* (corrupting its GST period), a fresh sequential number is consumed and the old one orphaned as a gap (GST expects gapless numbering), the primary key changes (invalidating `ReconciliationDiscrepancyEntity.invoiceVoucherId`), and any inventory link is permanently severed.

### H6. `deleteVoucher` never reverses stock for returns
`data/repository/AccountingRepository.kt:602-611`

Verified: branches only on `VoucherType.SALES` and `VoucherType.PURCHASE`. Deleting a `PURCHASE_RETURN` of 20 units leaves stock permanently 20 short; deleting a `SALES_RETURN` leaves it 20 over. Journal entries *are* correctly deleted for all 8 types, so this is stock corruption only — which makes it silent.

### H7. Voucher numbers can duplicate
> **VERIFIED → OVERSTATED.** The TOCTOU race is real; the count-based fallback is unreachable because the seed creates all 8 voucher configs and nothing deletes them.
`data/repository/AccountingRepository.kt:836-860`

The configured path reads `config.nextNumber` and writes back `+1` in a **separate** call — a TOCTOU race; a double-tap can post the same number twice. The fallback path derives the number from a live `COUNT + 1001`: with vouchers …/1001, /1002, /1003, deleting /1002 and creating a new one yields **"…/1003" — a duplicate of an existing voucher**. (CONFIRMED deterministic; the race itself is SUSPECTED.)

### H8. B2B/B2C classification is inverted by an operator-precedence bug
`ui/screens/ReportsScreen.kt:636` · `utils/CsvExporter.kt:41`

```kotlin
outboundSales.filter { it.partyName.contains("GSTIN", true) || user.gstin.isNotBlank() }
```

`user.gstin` is the **seller's own** GSTIN — non-blank for any registered business — so the `||` makes this `true` for every voucher. **100% of sales classify as B2B; B2C Large and B2C Small always show 0**, even for a purely retail business. The correct check (`partyGstin.length >= 15`) exists in `GstAutomationEngine.kt:148` but is never used by the UI or CSV path.

### H9. Credit and debit notes vanish from every GST computation
`GstAutomationEngine.kt:70-71` · `ReportsScreen.kt:635` · `AccountingDao.kt:193-198`

`SALES_RETURN` and `PURCHASE_RETURN` are never matched in GSTR-1, GSTR-3B or the Statutory GST tab, and there is no CDNR table in the export models. Credit notes simply disappear instead of netting off output tax — **GST liability is systematically overstated whenever returns occur.**

### H10. GST exports ignore the selected date range entirely
`GstAutomationEngine.kt:66-69` · `ReportsScreen.kt:635, 658`

`fp`/`retPeriod` is stamped with *today's* month while `getAllVouchersSync()` fetches **every voucher ever entered**, unfiltered. A year in, "Auto Export GST" sweeps all-time turnover into one month's return. The GSTR-1 tab labels its figures *"for {dateRangeState.displayLabel}"* while never filtering by it.

### H11. Tally XML contains no ledger postings — Tally cannot import it
`utils/TallyMargXmlUtil.kt:38-75`

Verified: the file contains **zero occurrences of `ALLLEDGERENTRIES`**. Tally requires `<ALLLEDGERENTRIES.LIST>` with `LEDGERNAME` / `ISDEEMEDPOSITIVE` / `AMOUNT` per line — that is how it knows what to debit and credit. This emits a flat `<AMOUNT>` plus `<GSTAMOUNT>` and `<ISINTERSTATE>`, which are not Tally tags and are ignored. Tally will reject the voucher or create an empty shell. The Marg export is an invented schema with the same missing structure.

### H12. B2C Large threshold is two years out of date
`ReportsScreen.kt:637` · `CsvExporter.kt:41` — `totalAmount > 250000.0`

Notification No. 12/2024-Central Tax reduced the interstate B2CL invoice-wise threshold from ₹2.5 lakh to **₹1 lakh**, effective **1 August 2024**.

### H13. GST rate slabs are outdated and 40% is missing
`GstCalculatorModal.kt:251` · `GstItemInputField.kt:84` · `VouchersScreen.kt:564, 1435`

All hardcode `0/5/12/18/28`. Following the 56th GST Council meeting (notified 17 Sept 2025, effective **22 Sept 2025**), the structure moved to essentially **5% and 18%**, plus a new **40% de-merit rate** for tobacco, pan masala and select luxury goods. Two largely-defunct slabs are offered as primary choices and there is **no 40% option at all**.

### H14. Filing deadlines ignore the QRMP scheme
`ui/screens/DashboardScreen.kt:2214-2258`

GSTR-1 is always shown due on the 11th, GSTR-3B on the 20th — correct for monthly filers only. QRMP filers (turnover ≤ ₹5 crore) are due on the **13th**, with GSTR-3B on the **22nd or 24th** by state category. `UserEntity` has no turnover or filing-frequency field, so the app cannot know. Every quarterly filer sees deadlines that don't apply and misses the ones that do. (CMP-08 on the 18th is correct.)

### H15. GSTR-1 CSV export corrupts its own TOTAL row
> ✅ **FIXED** — `CsvExporter.kt:47` now interpolates `${salesVouchers.size}`. Verified compiling.
`utils/CsvExporter.kt:47`

```kotlin
sb.append("TOTAL,,,$salesVouchers Invoices,...")
```

`$salesVouchers` interpolates the **entire `List<VoucherEntity>.toString()`** — the full object graph, commas included — into one CSV cell. Should be `${salesVouchers.size}`.

### H16. CSV group names are not escaped
> ✅ **FIXED** — added a central `csv()` RFC-4180 escaper and routed *every* free-text cell (name, groupName, partyName, narration, voucherNo, date) through it, rather than patching the one field. Verified compiling.
`utils/CsvExporter.kt:19` — `l.name` is quote-escaped, `l.groupName` is not, and it is free text. A group named `Office, "Rent" Expenses` terminates the field early and shifts every subsequent column.

### H17. The only real backup has no restore path
`service/AutoBackupWorker.kt:53-54`

The WorkManager job genuinely writes `AutoBackup_<ts>.json` and `auto_backup_latest.json`. Verified: those names appear **only in the writer** — nothing ever reads them. They live in app-private storage, are deleted on uninstall, and are never shared via FileProvider. Labelled "Cloud Backup"; neither cloud nor restorable.

### H18. Auto-backup defaults to on, but the worker is never scheduled
`ui/AccountingViewModel.kt:41-42, 449-455` — `autoCloudBackupState` defaults `true`, but `schedulePeriodicBackup` is only called from the Settings toggle handler. A user who never opens that screen sees a UI implying backup is on while **no job was ever enqueued**.

### H19. Reconciliation re-enables itself on every launch
`ui/AccountingViewModel.kt:153` — `schedulePeriodicReconciliation(getApplication(), true)`, hardcoded. Disabling it in Settings correctly cancels the job; the next launch silently re-enables it and runs a scan.

### H20. Exact-alarm and notification permissions are never requested
`service/PaymentReminderManager.kt:37-46` · `MainActivity.kt`

Verified: a repo-wide search for `canScheduleExactAlarms`, `checkSelfPermission`, `RequestPermission` and `POST_NOTIFICATIONS` returns **zero matches outside the manifest**. With `targetSdk = 36` neither is auto-granted. `setExactAndAllowWhileIdle` throws `SecurityException`, a blanket `catch` swallows it, and the UI clears the form as if scheduling succeeded. **The reminder never fires and the user is never told.** Notifications are equally silent.

### H21. Stock is never valued on the books, and Stock Status uses the wrong price
`ReportsScreen.kt:1253` · `AccountingRepository.kt:644-656`

- No journal entry ever references a Closing Stock ledger — stock is a quantity counter invisible to the Trial Balance.
- `avgCostPrice` is set once at item creation and **never recomputed on purchase**. Buy at ₹150 against a ₹100 setup cost and the basis stays ₹100 forever.
- "Total Stock Valuation" computes `stockQty * sellingPrice` — should be cost, baking unrealized margin into asset value. ✅ **FIXED** — now `stockQty * avgCostPrice`, relabelled "(at cost)". The other three parts of this finding (weighted-average recompute on purchase, closing-stock posting, COGS) remain **open**.
- No COGS is posted per sale (defensible under periodic inventory) — but the period-end closing-stock computation periodic inventory *requires* is never performed either.

### H22. Manually-created ledgers are silently mis-categorised, and editing one corrupts its real group
`data/repository/AccountingRepository.kt:147-173` · `AccountingModels.kt:111-112`
> **ESCALATED from Low after verification.**

`createLedger` builds `LedgerEntity` **without setting `groupName` or `category`**, so every manually-created ledger takes the entity defaults `"General Ledgers"` / `EXPENSE` regardless of the group the user picked. `LedgerManagementModal` displays those stale values *and pre-fills its Edit dialog from them* — so editing any field (even just an opening balance) re-submits "General Ledgers", and `updateLedgerDetails` reassigns `groupId` accordingly. This corrupts the real joined `ledger_groups` categorisation that every report depends on. Not cosmetic.

### H23. Tally XML import turns credit notes into sales `[V]`
`utils/TallyMargXmlUtil.kt:132-140`

The import `when` tests `contains("SALE")` first, so Tally's `"Sales Return"` (the real VCHTYPE for a credit note) becomes `VoucherType.SALES`, and `"Purchase Return"` becomes `PURCHASE`. Verified: the file produces `SALES_RETURN`/`PURCHASE_RETURN` **zero times**. Importing a credit note therefore *increases* output tax liability instead of reducing it — the opposite of its accounting meaning. Compounds H9.

### H24. GSTR-3B CSV violates the mandatory ITC set-off order `[V]`
`utils/CsvExporter.kt:60-64`

```kotlin
val netCgst = gstSummary.totalOutputCgst - gstSummary.totalInputCgst
val netSgst = gstSummary.totalOutputSgst - gstSummary.totalInputSgst
val netIgst = gstSummary.totalOutputIgst - gstSummary.totalInputIgst
```

Each head is netted independently. Under Rule 88A and s.49A/49B (Circular 98/17/2019-GST), **IGST credit must be exhausted first**, and CGST credit may never offset SGST liability or vice versa. Any business with interstate or import purchases gets a wrong "Net Tax Payable".

### H25. Two disagreeing B2B / interstate classifiers `[V]`
`data/gst/GstAutomationEngine.kt:90-102, 148` vs `ui/screens/ReportsScreen.kt:636` / `utils/CsvExporter.kt:41`

The JSON export classifies B2B from the party's **actual GSTIN** (`length >= 15`) and derives interstate by comparing state codes — sound logic. The CSV/PDF path instead uses the broken `user.gstin.isNotBlank()` heuristic (H8) and the manually-toggled `isInterstate` flag. The app can therefore bucket the *same invoice* differently in its two "GSTR-1" outputs.

### H26. "Undo delete voucher" corrupts GST rate and interstate status `[V]`
`ui/AccountingViewModel.kt:477-489`

```kotlin
gstRate = if (voucher.gstAmount > 0) 18.0 else 0.0,   // binary guess
isInterstate = false,                                  // always
```

Restoring a deleted voucher guesses the rate as 18-or-0 and forces `isInterstate = false`, discarding both. The codebase already knows how to derive the true rate (`importDataFromJson` does `(gstAmt / (amount - gstAmt)) * 100`); this path simply doesn't.

---

## MEDIUM

### M1. Cash Flow tab counts returns backwards
`ui/components/HierarchicalFinancialStatement.kt:398-403` — `"SALES_RETURN"` contains `"SALE"`, `"PURCHASE_RETURN"` contains `"PURCHASE"`. A refund paid to a customer counts as cash **in**. A ₹50,000 sale fully returned reports **₹1,00,000 net cash flow** instead of ₹0. It is also not a cash flow statement — it classifies by voucher type rather than by which ledger moved, so credit sales count as cash received, with no operating/investing/financing split.

### M2. No transaction wrapping on multi-step writes
`createVoucher` performs insert-voucher → GST detail → voucher items → stock update → journal entries → sync log as separate suspend calls with no `@Transaction` or `withTransaction {}` anywhere. A crash between steps leaves a voucher header with no journal lines (invisible to balances but present in lists), or a delete that restored stock but left the journal posted. Same for import (C6).

### M3. Intra vs inter-state is a manual toggle with no safeguard
`VouchersScreen.kt:585-589`. `isInterstateSupply(supplierState, recipientState)` exists at `utils/GstCalculationService.kt:76-79` and is **never called**. Meanwhile `GstAutomationEngine` derives interstate status from GSTIN at export time — so what was *posted* can silently disagree with what is *filed*.

### M4. GST rows are written for voucher types that can't have GST
`AccountingRepository.kt:288-303` — the `insertGstTaxDetail` block sits *before* the `when(voucherType)` switch, so it runs for RECEIPT/PAYMENT/CONTRA/JOURNAL too. `selectedGstRate` defaults to `"18"` and is never reset per type, so cash receipts commonly carry a spurious GST detail row and non-zero `gstAmount`. Statutory totals are safe (filtered by type), but invoice/receipt PDFs reading these fields would show fictitious tax lines.

### M5. No rounding anywhere; money held in `Double`
Zero matches for `round`/`BigDecimal` in any calculation path. `10000/1.18 = 8474.576271186441` is persisted unrounded. Section 170 of the CGST Act requires rounding to the nearest rupee.

### M6. No GSTIN validation anywhere
No validation function exists; GSTIN is free text and `"abc"` is accepted. Only the first two characters are ever inspected, and only at export. Blank GSTINs are silently replaced with invented ones — `"07AAAAA0000A1Z5"` at `SettingsScreen.kt:1488` and `"07AAAAA9999A1Z1"` at login — then shown on a "Government of India / GST Registration Certificate" styled modal.

### M7. CESS is seeded but never computed
`DatabaseSeedEngine.kt:68` creates a CESS ledger. `GstTaxDetailEntity` has **no cess field** and nothing computes it. Businesses selling cess-liable goods (automobiles, tobacco, aerated drinks, coal) invoice zero cess.

### M8. Two GST services, three GST calculators, mostly dead
`utils/GstCalculationService.kt` (live) vs `service/GstCalculationService.kt` (dead) — duplicate maths under different names. **Two** functions named `GstCalculatorModal` (not three, as first written); the "official" one's `onApplyToVoucher` **discards all four of its arguments**. `GstItemInputField` has no call sites. Maintainers will edit the wrong copy.

### M9. CONTRA direction is inverted for one phrasing
`AccountingRepository.kt:524` — matching `"cash to bank"` routes to the *withdrawal* branch, so a deposit described as "moving cash to bank" posts backwards. The Dashboard's quick-contra generates "Cash Deposit to Bank", which misses the substring and lands correctly by luck; the bug is live via the free-text party field on the Vouchers screen.

### M10. Reconciliation matches parties by exact string equality
`service/LedgerReconciliationWorker.kt:81-179`. "Sharma Traders" vs "Sharma Trader" never reconcile, producing spurious shortfall flags. Invoice-level matching needs the invoice number inside the receipt narration or tags. **Correction:** the Receipt form *does* render Narration and Tags (my original claim that it had no such field was wrong) — but there is no dedicated invoice-number field or autofill, so in practice everything falls through to a party-level aggregate net, where an overpayment on one invoice silently cancels an underpayment on another.

### M11. JSON import misclassifies every restored ledger
`AccountingRepository.kt:754-761` force-creates every unknown ledger name — vendors, GST, cash/bank alike — as `Sundry Debtors / ASSET` with a zero opening balance. On a fresh install (the "restore on a new phone" path) every vendor becomes a debtor, and the subsequent voucher replay then reuses those misclassified ledgers.

### M12. Dashboard "Easy Entry" hardcodes 18% GST on every Payment/Receipt `[V]`
`ui/screens/DashboardScreen.kt:1456-1463` passes `gstRateText = "18"` unconditionally, guaranteeing the spurious `GstTaxDetailEntity` rows described in M4 fire on ordinary cash transactions.

### M13. GST Calculator → Easy Entry discards everything `[V]`
`ui/screens/DashboardScreen.kt:1630-1636` — `onApplyToVoucher` receives `baseAmount, gstRate, isExclusive, isInterstate` and drops all four, opening a blank form. The calculator result cannot reach a voucher.

### M14. Every CONTRA creates a junk party ledger `[V]`
`getOrCreatePartyLedger` runs at `AccountingRepository.kt:270` for all voucher types, but the CONTRA branch never uses it — so each contra permanently adds a dead "Sundry Debtors" ledger named after its free-text description, polluting the ledger list and party autocomplete.

### M15. A third divergent cash-ledger group name `[V]`
`createCustomVoucher` (`AccountingRepository.kt:218`) uses `"Cash/Bank Accounts"`, alongside the seed's `"Cash-in-Hand"` and the repository's `"Cash-in-hand"`. Whichever path runs first wins the group association — same root cause as C2.

### M16. Balance-sheet drill-down shows unrelated vouchers as a "breakdown" `[V]`
`ui/components/HierarchicalFinancialStatement.kt:141-148` — `onSelectLedger` filters by the *hardcoded fake* ledger names from C1, matches nothing, then falls back to `vouchers.take(5)`, presenting five arbitrary vouchers as the breakdown of a fabricated line item.

### M17. Ledger group is free text, not a picker `[V]`
`ui/components/LedgerManagementModal.kt:361` is a plain text field despite a `commonGroups` list defined directly above it. With H22's stale pre-fill, any typo fragments `ledger_groups` ("Sundry Debtors" vs "Sundry Debtor") since lookup is exact-match.

---

## LOW

- **L1.** Unit test source set does not compile — `app/src/test/java/com/example/GreetingScreenshotTest.kt:24` references a `Greeting` composable that no longer exists. Pre-existing AI Studio scaffold leftover; `assembleDebug` unaffected.
- **L2.** `service/QuickActionsWidgetProvider.kt:76-88` launches an unstructured `CoroutineScope(Dispatchers.IO)` inside `onUpdate()` without `goAsync()`. Safe with no widget placed (verified), but a refresh may be dropped. **SUSPECTED.**
- **L4.** `rate = taxableValue / itemQuantity` writes `Infinity`/`NaN` if quantity is explicitly `0`. Doesn't affect journal balance. Verified reachable via **PURCHASE**, where the amount field is independent of quantity so the `amount > 0` guard doesn't block it.
- **L5.** `[V]` `ui/components/QuickEntryBottomSheet.kt` is **entirely dead code** — a fully-built composable with its own contra-account prediction wiring, never instantiated anywhere.
- **L6.** `[V]` Login accepts non-numeric input — `AccountingViewModel.kt:233,237` check only `.length`, never digits, so phone `"aaaaaaaaaa"` with OTP `"abcd"` succeeds. Extends X1.
- **L7.** `[V]` `finalTotalAmount` (`VouchersScreen.kt:184`) is written once and never read — direct evidence the correct exclusive-GST total is computed and then discarded. Supporting proof for C8.

---

## Honest-labelling issues

Not arithmetic bugs — places where the UI states something untrue. These need a product decision, not just a patch.

| # | Feature | Told to the user | What happens |
|---|---|---|---|
| X1 | **Login** `AccountingRepository.kt:100-114` | "Instant passwordless login • Secured via JWT" | Any 10-digit number + any 4-digit code logs in. No SMS is ever sent. The "JWT" is a concatenated string. A fake GSTIN is assigned. |
| X2 | **Scan Invoice / QR** `QrCodeScannerDialog.kt` | Live camera + OCR extraction | No camera, no OCR, **no barcode library in the build**. An animated laser line over a textbox pre-filled `"15000"`. Saved vouchers are tagged *"Scanned OCR Invoice"*. |
| X3 | **UPI Scan to Pay** `UpiQrCodeGenerator.kt:59-62` | "SCAN TO PAY — GPay, PhonePe, Paytm" | Verified: no QR library in dependencies. The data region is `hashCode`-XOR noise with no Reed–Solomon encoding. **The QR will not scan.** |
| X4 | **Contacts / Favorites** `VouchersScreen.kt:100-112` | "Select Customer from Contacts" | Hardcoded list of five fake names and numbers. No ContentResolver call anywhere. |
| X5 | **Excel/PDF OCR Import** `DashboardScreen.kt:1709-1746` | "Auto-fill bank statements" | No file picker, no parser. A textarea **pre-filled with 5 fictitious transactions**. "Import Valid Only" posts them as real vouchers. |
| X6 | **Scheduled Reminders** `DashboardScreen.kt:1286` | Your reminders | A hardcoded *"Anand Traders — ₹24,500 — Due Tomorrow"* card shown to everyone. The real list the user fills is never rendered. |
| X7 | **Monthly Financial Trend** `DashboardScreen.kt:2098` | 6-month history | 5 of 6 bars are the current total × hardcoded ratios `[0.45, 0.60, 0.52, 0.75, 0.85]`. |
| X8 | **On-device AI** `ai/LocalAiReconciliationEngine.kt` | "AUTOMATED ON-DEVICE AI GST ENGINE" | Regex with hardcoded replacements. On the app's **own demo string** it extracts tax rate `5.0` (matched inside "15000") instead of 18, state "18" instead of "07", and the invoice number as `"Sharma"`. Display-only. |
| X9 | **Crash Diagnostics** `TelemetryEngine.kt:197-206` | Your crash report | With no crash recorded, fabricates a fake stack trace instead of saying "none". |
| X10 | **User Analytics** `TelemetryEngine.kt:154-195` | Measured performance | Always the literal defaults 12ms/16ms. A real latency log is collected and ignored. |
| X11 | **Firebase Cloud Sync** `SettingsScreen.kt` | "restore on multiple devices" | No `google-services.json` ships. Every sync fails behind a 2-second toast. |
| X12 | **Telemetry REST sync** `ApiClient.kt:15` | Working backend | Points at `https://api.accounting.telemetry.internal/` — not resolvable. (Does report failure honestly.) |
| X13 | **Quick Actions** `DashboardScreen.kt:440-443` | "Reports", "P&L", "Print Invoice", "GST Summary" | All four open something else — the Overview tab or the Cash/Bank dialog. |
| X14 | **Dashboard voucher edit** `DashboardScreen.kt:894-901` | Tap to edit | Sets state for a dialog that doesn't exist in the file. Nothing happens. (Works in `VouchersScreen`.) |
| X15 | **Resolve discrepancy** `LedgerReconciliationModal.kt:44` | — | Pre-fills *"Verified with bank statement / client confirmed"*. Accepting the default records an attestation that may never have happened. |

Also: the PDF tax invoice is missing 7 of the 16 fields mandatory under **CGST Rule 46** — supplier address, buyer address, buyer GSTIN, place of supply, HSN/SAC, quantity, and the GST rate itself. A registered buyer cannot claim ITC on it. The signature image is stored in `cacheDir`, which Android may purge at any time, silently reverting future invoices to a blank signature.

---

## What is genuinely correct

Worth stating plainly — this is the foundation the rest can be rebuilt on.

- **Double-entry posting arithmetic** — all 8 voucher types produce exactly balanced journals, including zero-GST, inter-state and inventory-attached paths. CGST/SGST `/2.0` is lossless in IEEE-754, so no penny drift from the split.
- **Trial Balance** — real SQL aggregation over `journal_entries`, correct DR/CR opening-balance handling, correct treatment of a CR-balance asset. The 0.01 tolerance is standard practice.
- **Audit Trail** — genuine anomaly detection over real vouchers.
- **`createCustomVoucher`** — a correct generic two-ledger poster (the one C3 should have used).
- **GST split maths** — the inclusive formula and CGST/SGST vs IGST split are correct *where reached*.
- **Stock movement directions** — correct in all four voucher branches.
- **Analytics & Trends charts** — rebuilt earlier today from real ledger data; independently re-reviewed in this audit (sign conventions, month zero-fill, timezone, running-sum boundaries) with **no flaw found**.
- **`LedgerReconciliationWorker`** is read-only and cannot desynchronise the books. `PostalPincodeService`, `TelemetryMaintenanceEngine`, and `FirestoreSyncManager`'s own read/write code are real.
- **FileProvider paths** correctly cover every write location. No crash risk.

---

## Suggested order of work

1. **C4, C5, C6, C7, C11, H17, H18** — backup and restore (C11: restore also zeroes all stock). Nothing else matters if a user can lose their books. Start by deleting the fake success message.
2. **C8** — the GST under-charge. Every invoice raised today is wrong.
3. **C2, C3, H2, H4, M15** — all five share one root cause: `getLedgerByNameOrCreate` matches globally by name and silently discards the caller's requested group/category. Fix that function first. C3 additionally just needs to call `createCustomVoucher`.
3a. **H22** — ledger group corruption on edit (escalated from Low; corrupts real report categorisation).
4. **C1** — wire the Balance Sheet and P&L to real data, and remove the third party's business details from the source.
5. **C10, H8, H9, H10, H11, H23, H24, H25** — GST returns and the Tally/CSV exports.
6. **C9, H2, H3, H4, H5, H6, H7, H21** — posting correctness and inventory integrity.
7. **X1–X15** — decide per feature: build it, or drop the claim. Several are one-line deletions.
8. **H12, H13, H14, M5, M6** — statutory values that have drifted; consider making them configuration rather than constants.

---

*Every CONFIRMED finding was independently re-verified after the agent reported it — by direct code inspection, on-device reproduction, or executing SQL against the app's own database.*
