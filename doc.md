# Bill-Shill — Pipeline Analysis: What's Missing, What's Broken

**Date:** 12 August 2026
**Scope:** The end-to-end flows — voucher → ledger → reports → GST return — plus the inventory ON/OFF mode and the cash↔bank pipeline.
**Method:** Government/Tally/Vyapar source research to establish the correct standard, then four code traces, each claim re-verified independently against source.
**Companion docs:** `AUDIT_REPORT.md` (76 findings), `VERIFICATION_REPORT.md` (adversarial re-test).

---

## The one-paragraph answer

The **posting engine is arithmetically sound** — every voucher balances. Everything that decides *which* accounts get posted, *which mode* the business is in, and *how* a sale reaches a GST return is broken or absent. Most importantly: **the invoicing screen never captures the buyer's GSTIN or state**, which silently disables the entire GST classification layer downstream — a correctly-posted inter-state sale is re-split as CGST+SGST in the export, sending tax to the wrong government account. And **"inventory OFF" is not an accounting mode** — it hides a dropdown and a tab; the ledgers behave identically.

---

# PART 1 — THE STANDARD (what should happen)

## 1.1 Inventory ON vs OFF

| | Inventory ON (trading) | Inventory OFF (service) |
|---|---|---|
| Sales posts to | Sales A/c (Sales Accounts group) | Sales / Service Income — a **naming & grouping** choice, same voucher type |
| Purchase posts to | **Purchase A/c** (direct expense, feeds Trading A/c) | **Direct or Indirect Expenses** — a service business has *no* Purchase Account |
| Stock movement | Yes, on invoice save | None — no stock items exist |
| COGS | **Not** posted per sale. Derived by Trading A/c: `Opening + Purchases − Closing` | N/A |
| Trading A/c / Gross Profit | Shown | **Must not be shown.** P&L only: Income − Expenses = Net Profit |
| Closing Stock line | Shown on Balance Sheet | Must not appear |

Tally gates this on **F11 → "Maintain Accounts Only" vs "Accounts with Inventory."** Vyapar gates it **per item** (Product vs Service), which is more flexible and supports hybrid businesses.

> **The classic implementation mistake** — and the one this app makes — is routing a service business's consumables into a "Purchase Account." That drags a Trading-Account ledger into a P&L-only structure and corrupts Gross Profit the moment a Trading Account is introduced.

## 1.2 Standard voucher templates (intra-state; IGST replaces CGST+SGST inter-state)

| Voucher | Dr | Cr |
|---|---|---|
| **Sales** | Party (taxable + tax) | Sales A/c (taxable) · Output CGST · Output SGST |
| **Purchase** | Purchase A/c (taxable) · Input CGST · Input SGST | Party (taxable + tax) |
| **Receipt** | Bank/Cash | Party (debtor) |
| **Payment** | Party (creditor) / Expense | Bank/Cash |
| **Contra** | Bank *(deposit)* / Cash *(withdrawal)* | Cash *(deposit)* / Bank *(withdrawal)* |
| **Journal** | X | Y — no cash/bank leg at all |
| **Credit Note** (sales return) | Sales Return (taxable) · Output CGST · Output SGST | Party |
| **Debit Note** (purchase return) | Party | Purchase Return (taxable) · Input CGST · Input SGST |

**Output and Input tax must be separate ledgers.** Output tax is a *liability*; Input tax (ITC) is a *recoverable asset*. Sharing one ledger nets them and misclassifies the Balance Sheet.

## 1.3 Cash ↔ Bank (your specific question)

- **Cash deposited into bank** → `Dr Bank / Cr Cash`
- **Cash withdrawn from bank** → `Dr Cash / Cr Bank`

**It must be a Contra voucher.** Tally by default *refuses* to let a Payment or Receipt voucher hold two cash/bank ledgers — there's an explicit opt-in override its own docs describe as flexibility, not the recommended path. Using Payment/Receipt misclassifies the transaction across every report that keys off voucher type.

**A contra is an internal transfer.** One voucher updates both the Cash Book and the Bank Book atomically, and it **must net to zero in the Cash Flow statement** — it is never an operating, investing or financing flow.

## 1.4 Sales → GSTR-1 → GSTR-3B

| Invoice condition | GSTR-1 table |
|---|---|
| Buyer GST-registered (any state, any value) | **4A — B2B** |
| Buyer unregistered, **inter-state**, value **> ₹1,00,000** | **5A — B2CL** |
| Buyer unregistered, intra-state, or inter-state ≤ ₹1L | **7 — B2CS** (consolidated by state+rate) |
| Credit/debit notes to registered buyers | **9B — CDNR** |
| HSN-wise summary | **12** |
| Invoice numbers issued *and cancelled* | **13** |

**B2CL threshold = ₹1,00,000 from 1 Aug 2024** (Rule 59(4), Notification 12/2024-CT). ⚠️ The government's own `tutorial.gst.gov.in` B2CL help page is **stale and still says ₹2.5 lakh** — do not "correct" the code back to it by citing that URL.

**HSN Table 12:** 6-digit if AATO > ₹5 cr, else 4-digit; mandatory for all B2B; **dropdown-only, manual entry disallowed** since May 2025.

### The architectural change most implementations have missed

- **Since July 2025**, GSTR-3B Table 3.1 outward liability is **hard-locked** — auto-populated from GSTR-1, non-editable. Corrections go through **GSTR-1A before** filing 3B.
- **From July 2026**, Table 4A ITC is auto-populated from GSTR-2B and **soft-locked** — edits remain possible but are logged, and an upward edit above the GSTR-2B figure triggers a **DRC-01C notice under Rule 88D**. *(Corrected: an earlier draft of this doc called it "non-editable." Only Table 3.1's July 2025 lock is a hard portal-side block; the two mechanisms are different.)*
- **Since Oct 2024**, **IMS** sits between the supplier's GSTR-1 and your GSTR-2B — the recipient's Accept/Reject/Pending gates what's claimable.
- **Sequential filing is mandatory** — GSTR-1 before GSTR-3B.

> **Consequence:** an app that computes GSTR-3B independently from its own ledger is modelling a world that no longer exists. Correct flow is **GSTR-1 upstream of 3B liability; GSTR-2B/IMS upstream of 3B ITC.**

## 1.5 Purchase → ITC (s.16(2))

ITC is claimable only if: valid invoice **and** goods/services received **and** *supplier actually paid the tax* **and** recipient filed GSTR-3B. Plus two clocks a general ledger doesn't naturally carry:
- **Rule 37 / 180 days** — unpaid supplier ⇒ reverse ITC with interest, re-claim on payment.
- **s.16(4)** — claim by 30 Nov following FY end, or annual-return date, whichever is earlier.

---

# PART 2 — WHAT THE APP ACTUALLY DOES

## 2.1 Inventory ON/OFF — **cosmetic, not an accounting mode**

`AccountingRepository.createVoucher` — the entire posting engine — **never reads `enableInventory` or `businessType`.** Every read in the codebase:

| Site | Effect |
|---|---|
| `VouchersScreen.kt:430` | hides the item picker |
| `ReportsScreen.kt:56,57,176` | hides the Stock Status tab |
| `SettingsScreen.kt:259-280` | draws the toggle |
| `DashboardScreen.kt:187` | prints the text "TRADING MODE" |

Consequences for a **service business**:
- Sales still post to `"Sales Account"` (`AccountingRepository.kt:315`) — no service-income ledger exists anywhere.
- Purchases still post to `"Purchase Account"` (`:403`) — **violates 1.1**; consumables should be Direct/Indirect Expenses.
- Trading Account, Gross Profit, Opening Stock and Closing Stock are rendered **unconditionally** — `HierarchicalFinancialStatement.kt` never reads either field.
- Stock movement is gated only on `selectedItemId != null`, never on the toggle.

**`businessType` (SERVICE/TRADING) controls nothing at all** — its one read is a Dashboard text label.

### Two stores, one dead, and a reset on every login

`UserEntity.enableInventory` (Room) is what the UI reads. `UserSettingsDataStore.enableInventoryFlow` feeds `persistentInventoryMode` (`AccountingViewModel.kt:29`) — **never consumed anywhere**. Both are written on toggle, so they agree… until login.


### ✅ SOLUTION — agreed after challenge

**Chosen: a company-wide toggle (Tally F11 model), read from the DAO inside the repository.**

*Rejected — Vyapar's per-item Product/Service flag.* It has nothing to consult on the most common transaction: `voucher_items` rows are only written when an item is picked, so an amount-only sale carries no item to read a flag from. Per-item becomes viable only after line items are mandatory everywhere.

*Changed by the challenger — this is the important part.* The first design threaded `enableInventory` as a **parameter** through `createVoucher`. The challenger counted the real cost: **6 `createVoucher` + 8 `addVoucher` = 14 threading points**, and a missed one silently posts to the wrong ledger — precisely the "caller-supplied data silently dropped" failure class that already produced C2, C3, H2, H4 and H22. **Read it once, authoritatively, inside the repository instead:**

```kotlin
// AccountingRepository.createVoucher — no new parameter, no threading, nothing to forget
val inventoryEnabled = dao.getUserSync()?.enableInventory ?: true
```
This is the codebase's own established pattern (`GstAutomationEngine.kt:49` already does `accountingDao.getUserSync()`).

**Then branch the ledger selection:**
```kotlin
val destinationLedger = when {
    isAssetPurchase   -> getLedgerByNameOrCreate(partyName, "Fixed Assets", LedgerCategory.ASSET)
    inventoryEnabled  -> getLedgerByNameOrCreate("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE)
    else              -> getLedgerByNameOrCreate("Consumables & Direct Expenses", "Direct Expenses", LedgerCategory.EXPENSE)
}
```
Same conditional in `PURCHASE_RETURN`. Sales may optionally use `"Service Income"` when inventory is off — that is a naming/grouping choice, not a compliance requirement.

**Delete the dead dual store** (confirmed dead by two independent greps): `UserSettingsDataStore.enableInventoryFlow` / `businessTypeFlow` / `saveInventoryMode`, and the ViewModel's `persistentInventoryMode` / `persistentBusinessType`. Also delete `saveBusinessName`/`KEY_BUSINESS_NAME` — a third write-only copy with no reader.

**Honest limit:** this changes *nothing visible* on the Balance Sheet or P&L until **C1** is fixed, because those statements are hardcoded literals and never read the ledger at all. Ledger routing is a prerequisite for C1's fix, not an independent win.

## 2.2 🔴 NEW CRITICAL — every login destroys the business profile

`AccountingRepository.loginWithOtp` (`:100-114`) builds a fresh `UserEntity` and calls `dao.saveUser()`, which is `@Insert(onConflict = REPLACE)` against the **constant** primary key `"primary_user"` — a whole-row replace. Only 7 fields are named; the rest fall back to data-class defaults.

| Field | After ANY login |
|---|---|
| `businessName` | `"Indian Enterprise"` |
| `gstin` | **`"07AAAAA9999A1Z1"` — fabricated** |
| `address`/`city`/`state` | `"123 Commercial Complex"` / New Delhi / Delhi |
| `businessType` / `enableInventory` | TRADING / true |

A merchant configures their real name, GSTIN and address → logs out → logs back in → it's all demo data. **Every invoice afterwards carries a fake GSTIN** — invalid under Rule 46, blocks the buyer's ITC, and is a false statutory declaration. Combined with login accepting *any* 10-digit number and *any* 4-digit code, this is the normal path, not an edge case.


### ✅ SOLUTION — agreed after challenge

**Chosen: a scoped `UPDATE` of session columns only — plus the same fix applied to the seed engine.**

*Rejected — fetch-then-`.copy()`.* It fixes today's bug, but it's a read-modify-write that depends on every future maintainer remembering the pattern; one edit back to `UserEntity(...)` silently reintroduces C13. A 3-column `UPDATE` is *structurally incapable* of resetting profile fields.

```kotlin
// AccountingDao
@Query("UPDATE users SET phoneNumber = :phone, token = :token, isLoggedIn = 1 WHERE id = 'primary_user'")
suspend fun updateLoginSession(phone: String, token: String): Int

// AccountingRepository.loginWithOtp
val rows = dao.updateLoginSession(phoneNumber, token)
if (rows == 0) dao.saveUser(UserEntity(phoneNumber = phoneNumber, token = token, isLoggedIn = true))
```
Logout is unaffected — `logoutUser()` is a separate query (`AccountingDao.kt:70-71`). *(One challenge question — "does hardcoding `isLoggedIn=1` break logout?" — was checked and is a false alarm.)*

**⚠️ The challenger found this fix incomplete, and it is.** `DatabaseSeedEngine.seedDefaultData` null-checks, then does its **own unconditional `dao.saveUser(...)`** at line 84 — and `AppDatabase.kt:122` launches the seed **fire-and-forget**. So a first login can insert a real row that the seed coroutine (already past its null-check) then REPLACEs with the demo profile. **Patching `loginWithOtp` alone does not close C13.** The seed's `saveUser` must be guarded too, or seeding and first login made mutually exclusive.

**Blank the fabricated defaults — but only together with the two dormant fallbacks, or it backfires.**

There are **five** fake GSTINs. Two of them (`HierarchicalFinancialStatement.kt:67` → `23ADOPS9429A1ZE`, `SalesInvoiceDialog.kt:156` → `23BNJPS3408M1ZP`) are `.ifBlank { … }` fallbacks that are **dormant today precisely because the entity default is non-blank**. Blanking `UserEntity.gstin` without deleting them makes a fake GSTIN **the default rendering** on the Balance Sheet and on a dialog titled "TAX INVOICE" — a net regression. All five must go in one change.

**Payoff:** `ReportsScreen.kt:436`'s `highValueMissingGstin` compliance check currently **can never fire**, because `user.gstin` can never be blank. Blanking the defaults switches on a warning the app already built.

**Also fix in the same pass:** `PdfInvoiceGenerator.kt:68` will render `", ,  | Ph:"` once address/city/state are blank — needs a join-non-blank helper. And `gstStatus → "UNVERIFIED"` is pointless alone: `SettingsScreen.kt:1536` renders it inside a **hardcoded green** badge on a "Government of India / GST Registration Certificate" card, so the word would contradict the colour. Fix the badge or leave both.

## 2.3 🔴 NEW CRITICAL — the buyer's GSTIN is never captured, which disables GST classification

**Verified:** `VouchersScreen.kt` contains **zero** buyer-GSTIN input (every `gstin` match is the substring inside `isGstInclusive`). `getLedgerByNameOrCreate` (`:579-600`) sets `name`, `groupId`, `groupName`, `category`, `openingBalance`, `balanceType` — **never `gstin`, never `state`**.

> **Correction to an earlier statement of mine.** GSTIN is not *unstorable* — `LedgerEntity` has `gstin`/`state`/`city` columns, and `LedgerManagementModal.kt:423` does expose a GSTIN field that persists (`:463,475`). The gap is narrower and more specific: **the invoicing flow never asks for it, and party ledgers auto-created by typing a new name on an invoice are born blank.** A user would have to separately visit Chart of Accounts → Ledger Management and edit each party by hand. In practice that means blank, which is what disables everything below. The fix is therefore mostly *surfacing an existing field at the right moment*, not building a new party model from scratch.

Downstream consequences:

1. **`GstAutomationEngine`'s B2B test (`partyGstin.length >= 15`) never passes.** Every customer — including genuinely registered ones — is bucketed **B2CS**.
2. **Worse: the export re-derives `isInterstate` from the party ledger's blank state**, not from `voucher.isInterstate` (`GstAutomationEngine.kt:90-102`, falling to `else -> clientStateCode`). A ₹1,50,000 inter-state sale, correctly posted as **₹22,881.36 IGST**, is re-split in the export as **₹11,440.68 CGST + ₹11,440.68 SGST** — tax remitted to the wrong government entirely.


### ✅ SOLUTION — agreed after challenge

**Chosen: extend `LedgerEntity`, snapshot place-of-supply onto the voucher, and capture GSTIN at invoice entry.**

*Rejected — a first-class `PartyEntity`.* `partyName` is a string join key in **12+** places (`getOrCreatePartyLedger`, the asset-detection branch, every GST ledger lookup, `GstAutomationEngine.kt:90,240`). ERPNext splits Party from Account for multi-company and multi-address needs that don't exist here; Tally itself puts GSTIN on the party ledger. The split is a far larger refactor for no GSTR-1 benefit.

**1. Type the registration, don't infer it from GSTIN length.**
```kotlin
enum class GstRegistrationType { UNKNOWN, REGULAR, COMPOSITION, UNREGISTERED, CONSUMER,
                                 SEZ_UNIT, SEZ_DEVELOPER, OVERSEAS, UIN_HOLDER }
// LedgerEntity += gstRegistrationType, gstStateCode
```
`partyGstin.length >= 15` cannot distinguish a Regular customer from an SEZ one — both have valid 15-char GSTINs — which is exactly why SEZ zero-rated sales currently land in B2CS. An enum also makes `isSez && isOverseas` unrepresentable.

**2. Snapshot POS onto the voucher; never re-derive at export.**
```kotlin
// VoucherEntity += partyGstin, posStateCode, partyGstRegistrationType
```
Resolved **once** at posting by a `PlaceOfSupplyResolver` (SEZ ⇒ always inter-state per s.7(5) IGST Act; overseas ⇒ code `"97"`; otherwise state-code comparison, with a manual override for the s.12(3)–(13) service categories). Then **delete** `GstAutomationEngine.kt:90-102`'s re-derivation. That block is the direct cause of the ₹22,881 IGST → CGST+SGST corruption.

**⚠️ Challenger's condition:** the resolver must drive **the actual posting**, not just the export snapshot. If the manual `isInterstate` toggle survives alongside it, you get a new version of the same disagreement on one row — better field names, same bug.

**⚠️ Trade-off the challenger surfaced:** snapshotting forecloses repair. Today a GSTIN added later via Ledger Management is picked up at export; after snapshotting, it isn't. So snapshotting is only safe **paired with** capturing GSTIN at voucher entry — which is the real fix here anyway.

**3. Capture it where it's actually needed.** The field already exists and persists (`LedgerManagementModal.kt:423`, `createLedger` line 22) — it's simply never asked for during invoicing, and `getOrCreatePartyLedger` creates parties blank. Add GSTIN + registration-type chips + state to `VouchersScreen` Step 1, shown only for SALES/PURCHASE/returns, and pass them into `getOrCreatePartyLedger` so auto-created parties are born complete.

**4. `GstinValidator` as a pure total function** — regex + Luhn mod-36 checksum, blank is valid (B2C is legal), never throws. Gate only `Button.enabled`.

> **Never put `require()` in a data-class `init{}`.** Room instantiates entities from a cursor on **every read**, so a validating constructor would crash on merely *opening the ledger list* against legacy rows — not just while typing.

**5. Fix the silent Delhi default.** `GstAutomationEngine.kt:393` maps any unrecognised state name to `"07"`. Promote the table to a shared `GstStateCodes` object returning **null** on miss, and surface it as a data-quality error instead of guessing.

## 2.4 🔴 NEW — most invoices have no line items, so HSN is unfixable as-is

`voucher_items` rows are written **only when an inventory item is explicitly picked** (`AccountingRepository.kt:330-348`). A normal amount-only sale writes none — no HSN, no quantity, no per-line rate.

This is the **root cause** beneath the fabricated HSN tables: even after deleting the hardcoded `"9983"` and the invented 60/25/15 split, **there is no data to read**. Table 12 cannot be produced until every invoice carries line items.


### ✅ SOLUTION — agreed, but deliberately narrowed after challenge

**Chosen: make line items mandatory *only where a capture UI exists*, and exclude everything else from Table 12 with a visible banner.**

*Rejected — "mandatory on every SALES/PURCHASE" as originally proposed.* The challenger counted the call sites: **4 of 8 `addVoucher` entry points can post SALES/PURCHASE with no item data at all** — the QR scanner (`MainActivity.kt:88`), both bulk-import flows (`DashboardScreen.kt:1927,1954`), and the Tally XML import (`ReportsScreen.kt:1641`). A blanket mandate either hard-breaks those flows or forces a **synthetic placeholder line** — which is C10 relocated, and *worse*: today's `"9983"` is a display-time computation you can delete and retroactively fix, whereas a fabricated line item is **stored data** a later Table 12 export would treat as fact.

**So:**
- Add real item capture to `VouchersScreen` (with an inline "quick add service item" — name, HSN/SAC, rate — so a service invoice isn't forced through catalogue management).
- Leave the four import/scan paths writing no line item, and **exclude those vouchers from Table 12** with an explicit *"N invoices missing HSN — cannot be included, review before filing"* banner.
- Never synthesise a line to satisfy a constraint.

**Schema:** `VoucherItemEntity.itemId` becomes nullable with `ON DELETE SET NULL`; add snapshot `description`, `hsnSacCode`, `unit`, `itemType`. Snapshot rather than join live, so a January invoice still reports January's HSN after the item master is edited.

**🚨 Highest-risk item in the entire plan.** This requires a **table rebuild** (SQLite can't alter nullability or FK actions in place). The challenger established: `exportSchema = false`, **no `/schemas` directory exists**, **no `MigrationTestHelper` anywhere**, and the two existing migrations only `CREATE TABLE IF NOT EXISTS` standalone tables — **this codebase has never rebuilt a table carrying FKs and indices**. Room still validates the schema hash at every open, so a column-order, nullability, FK-clause or auto-generated index-name mismatch crashes **every user** on first launch after update.

**Do not ship this without first** setting `exportSchema = true`, committing the schema JSON, and writing a `MigrationTestHelper` test against a hand-seeded v8 database.

## 2.5 Cash ↔ Bank pipeline — broken in three places

1. **The cash ledger is split.** The code looks up `"Cash-in-hand"`; the seed created `"Cash in Hand"`. Exact-match, case-sensitive. **Proven at runtime:** one ordinary ₹7,000 Contra created a 13th ledger *and* a 13th group and posted to the empty duplicate. Trial Balance now shows both:

   | Ledger | Group | Dr | Cr |
   |---|---|---|---|
   | `Cash in Hand` | Cash-in-Hand | 50,000.00 | – |
   | `Cash-in-hand` | Cash-in-hand | – | **7,000.00** |

   A **credit balance on a cash account** — an accounting impossibility. The user's real cash ledger shows *no movement at all*; opening it shows nothing. The Dashboard's ₹43,000 total is right only by accident, because `LIKE '%Cash%'` sums both.

2. **Cash-vs-bank is guessed from the customer's name** — `partyName.lowercase().contains("cash")`. "Cashmere Textiles Pvt Ltd" paying by NEFT posts to the cash drawer. `VouchersScreen` has **no payment-mode selector at all**; `DashboardScreen.kt:1454` works around this by prefixing `"Cash - "` to deliberately trigger the bug.

3. **Contra is mishandled in Cash Flow.** Per 1.3 it must net to zero, but the Cash Flow tab classifies by voucher-type substring — `"SALES_RETURN"` contains `"SALE"` — so a ₹50,000 sale fully returned reports **₹1,00,000 net cash flow** instead of ₹0.

   Worse, it **double-counts every credit sale**: a ₹11,800 credit sale matches `contains("SALE")` and is booked as an inflow immediately — before any money arrives. When the customer later pays, the RECEIPT matches `contains("RECEIPT")` and is booked *again*. The tab shows **₹23,600 of "cash inflows" for a single ₹11,800 cash event.** Same mirror-image bug on PURCHASE + PAYMENT.

   CONTRA is excluded from the tab entirely — which is coincidentally the right *outcome* (§1.3: transfers net to zero) but for the wrong reason: `"CONTRA"` simply matches none of the four substrings. Rename a future voucher type to `BANK_PAYMENT` and it would silently start appearing as an outflow.

### Direct answer: "will it show correctly on the cash/bank screens?"

**The two Dashboard tiles are correct — but only by accident.** They use `LIKE '%Cash%'`, and SQLite's `LIKE` is case-insensitive, so both cash ledgers get summed together and the total comes out right. Every surface that *isn't* a blind sum exposes the fracture:

| Surface | What it shows after a ₹20,000 cash deposit |
|---|---|
| Dashboard Cash tile | ₹30,000 — **correct by accident** (sums both ledgers) |
| Dashboard Bank tile | ₹2,70,000 — correct |
| **Cash & Bank Ledger dialog** | Three rows: `Cash in Hand` ₹50,000 Dr (looks untouched) · `Cash-in-hand` **₹20,000 Cr** (a cash account shown as overdrawn) · `HDFC Bank Ltd` ₹2,70,000 Dr |
| **Cash Book** (tap `Cash in Hand`) | **"No journal entries for this ledger yet"** — while the header above it still reads "Closing Bal: ₹50,000.00" |
| Trial Balance / Chart of Accounts | The same split, as two separate ledgers |
| Cash Flow tab | Contra absent entirely |

So the user's instinct is right: the money nets correctly in the headline, but **the moment they open the cash ledger to check, they see nothing** — and a second, near-identically-named ledger carrying a negative balance.

Three further defects found in this trace:
- **A CONTRA entered via `VouchersScreen` can carry a spurious 18% GST row** — `selectedGstRate` defaults to `"18"` and is never reset when the voucher type changes.
- **Easy Entry's `"Cash - "` prefix creates junk party ledgers** named `"Cash - Office Rent"` in Sundry Debtors, every time cash mode is used.
- **The party autocomplete offers `Cash in Hand` and `HDFC Bank Ltd` as *party* names** — picking "Cash in Hand" as the Payer on a Receipt produces a self-referential entry where cash pays itself.


### ✅ SOLUTION — agreed after challenge

**Chosen: stable ledger identity + an explicit payment mode + a dedicated Contra path + a ledger-derived Cash Flow.**

**1. `systemCode` on the ledger — not renaming the strings.**

*Rejected — make the four spellings agree.* This bug **is** the proof that approach fails: someone already tried to keep names in sync across two files. And a user renaming their cash ledger — which Tally explicitly permits — re-breaks it immediately.

```kotlin
// LedgerEntity += systemCode: String?      ("CASH" | "BANK_DEFAULT" | null)
// LedgerGroupEntity += kind: LedgerGroupKind { CASH, BANK, CUSTOM }
```
Lookups go through `getOrCreateSystemLedger(systemCode, …)`, never a name.

**Migration must re-point before deleting** — `journal_entries.ledgerId` is `RESTRICT`, so history moves first, then the duplicate row goes.

**⚠️ Three challenger corrections, all adopted:**
- There are **3** group spellings (`Cash-in-Hand`, `Cash-in-hand`, `Cash/Bank Accounts`), not 4 — and `createCustomVoucher`'s is an **open-ended namespace** (free-text dropdown), so exact-name enumeration can never be complete.
- **"Oldest id wins" can eat a legitimate ledger.** `LedgerManagementModal.kt:351` offers `"Cash-in-hand"` as a group suggestion, so a user's genuine second cash till can carry the exact colliding string. Require confirmation for merge candidates rather than merging blindly on string equality.
- **Guard the orphan-group delete**: `SELECT COUNT(*) FROM ledgers WHERE groupId = :orphan` and skip if non-zero, or the `RESTRICT` FK throws mid-migration.
- `getOrCreateSystemLedger` is check-then-insert — the **same TOCTOU shape as H7**. Handle the unique-index conflict by re-fetching, don't let it crash.

**2. Payment mode: an explicit ledger id, surfaced as chips.**
Data model is "which ledger" (Tally's own model); UI is a Cash/Bank chip pair today, expanding to a dropdown once more than one bank ledger exists. This retires `DashboardScreen.kt:1454`'s `"Cash - "` prefix hack, which also stops creating junk `"Cash - Office Rent"` debtor ledgers.

**3. A dedicated `createContraVoucher(fromLedgerId, toLedgerId, …)`**, with the CONTRA arm deleted from `createVoucher`. One change closes four defects: no substring direction-guessing, no junk party ledger (there is no `getOrCreatePartyLedger` call on this path), no spurious GST row (no `insertGstTaxDetail` call exists), and the invariant holds for **every** caller including the JSON-import replay.

**⚠️ Do not hard-reject on `kind == CUSTOM`.** Group names are free text, so a business adding a second bank as `"ICICI Savings"` gets `CUSTOM` — and could then never record an inter-bank transfer, the single most common Contra for a two-bank business. Ship a one-time "classify your account groups" step during migration first, or infer with a manual override.

**4. Cash Flow derived from ledger movement, not voucher type.**
Reuse the movement queries behind `cashBankTrendFlow` with period bounds. Contra then nets to zero **by arithmetic**, and the Cash Flow tab, the trend chart and the Trial Balance can no longer disagree.

**⚠️ The challenger caught a self-contradiction:** those queries still use `LIKE '%Cash%'` — the very pattern `systemCode`/`kind` exists to kill. **All 7 `LIKE`-based DAO queries must be rewritten** to filter on `kind`/`systemCode`. Proof it matters: **`"Cash Discount Allowed"`**, a standard Tally indirect-expense ledger, matches `LIKE '%Cash%'` and would be summed as cash movement.

**5. Party autocomplete** filters on `kind != CUSTOM` rather than keyword matching.

## 2.6 Sales → GSTR pipeline: break-point table

| Hop | Should happen | Actually | Verdict |
|---|---|---|---|
| Exclusive/Inclusive toggle | Exclusive adds tax on top | `addVoucher` has **no** such parameter; everything treated as inclusive | **BROKEN** (C8) |
| Preview total | Show ₹11,800 | Shows `amount` (₹10,000); `finalTotalAmount` computed then discarded | **BROKEN** |
| Buyer GSTIN/state capture | Required for B2B + place of supply | **No field exists** | **BROKEN** (new) |
| `vouchers` write | `totalAmount` = grossed-up | Stores raw ₹10,000 | **BROKEN** (C8) |
| `journal_entries` | Balanced | Balances exactly | **OK** |
| `voucher_items` | HSN/qty per line, always | Only if an item is picked | **BROKEN** (new) |
| `getGstSummaryFlow` | Period-filtered, returns netted | **No date filter at all**; returns never subtracted | **BROKEN** (H9, H10) |
| B2B/B2C split | By buyer GSTIN | `user.gstin.isNotBlank()` — the **seller's** own — makes 100% B2B | **BROKEN** (H8) |
| HSN summary | Real per-item HSN | Two different fabrications | **BROKEN** (C10) |
| ITC set-off | IGST-first (Rule 88A) | Each head netted independently | **BROKEN** (H24) |

### The three GST paths disagree

Same books, three answers. Worked example (V1 ₹10k registered intra, V2 ₹5k unregistered intra, V3 ₹1.5L unregistered **inter**, V4 ₹2k credit note):

| Path | Output tax | B2B/B2CL/B2CS | Notes |
|---|---|---|---|
| `getGstSummaryFlow` (Statutory tab, 3B CSV) | ₹25,169.49 | n/a | Overstates by ₹305.08 — credit note vanished |
| GSTR-1 tab / CSV | ₹25,169.49 | **all three → B2B** | B2CL and B2CS always empty |
| JSON export | ₹25,169.49 | **all three → B2CS** | V3's ₹22,881 IGST **re-split as CGST+SGST** |

They coincidentally agree on the grand total (itself wrong) and disagree on every breakdown that matters for filing.


### ✅ SOLUTION — agreed after challenge

**Chosen: one classifier, one aggregation, GSTR-3B as a labelled preview.**

*Rejected — patch each of the three paths.* Three independently-maintained implementations will re-diverge on the next edit. And the audit's own warning applies: adding a fourth implementation alongside makes it worse.

**1. A single `GstClassifier`** returning a sealed `Classification { B2B, B2CL, B2CS, Unclassifiable }`, with **all three existing call sites deleted** — `ReportsScreen.kt:636`, `CsvExporter.kt:53`, and `GstAutomationEngine.kt:90-102,148`. `Unclassifiable` is a real state surfaced as a data-quality item, never silently defaulted to the seller's own state.

**2. `TaxPeriod` ("MMyyyy") frozen onto the voucher**, and every GST query filtered by it — replacing `getAllVouchersSync()`. `DateRangeFilterState` stays for analytics; statutory exports use whole periods.

**⚠️ Challenger: this collides with H5 and cannot ship before it.** `updateVoucher` is delete-plus-recreate with no `date` parameter, so **editing any voucher already resets its date to now**. Freeze `taxPeriod` on top of that and `date` and `taxPeriod` disagree permanently. Policy adopted: `taxPeriod` **recomputes** on a date edit, and the edit is rejected if **either** the old or the new period is locked — otherwise a user can move a voucher *out* of a locked period and silently change filed totals.

**3. GSTR-3B becomes a preview**, computed by summing the app's own GSTR-1 aggregation rather than a second independent ledger walk.

**⚠️ Two challenger caveats, both adopted.** First, **I overstated the July 2026 change** and the doc now says it correctly: Table 4A is a **soft lock** — edits are allowed but logged, and an upward edit above the GSTR-2B figure triggers **DRC-01C under Rule 88D**. Only Table 3.1's July 2025 lock is a hard portal-side block. Second, this is a **packaging fix, not an accuracy fix**: it kills the three-way disagreement but ships no correctness gain until B2CL, CDNR and period filtering land. Sequence it after those, and keep the figure reactive so merchants don't lose their mid-month liability number.

**4. Returns → linked CDNR via `originalVoucherId`**, not aggregate subtraction — Table 9B needs per-note fields (note no./date, original invoice, reason) that a subtraction can never produce. Unlinked legacy returns net the total only and are flagged "CDNR entry incomplete."

**5. `SupplyNature` enum** rather than inferring exemption from `gstAmount == 0`, which conflates nil-rated, exempt, zero-rated export and a plain ₹0 data-entry error.

**6. `Rule88ASetOff`** implementing IGST-first exhaustion, with CGST↔SGST cross-utilisation impossible by construction. **Must not ship before the rounding/money-type fix** — otherwise `coerceAtLeast(0.0)` silently swallows drift.

**7. Fabricated values:** `ty = "OTH"` immediately; real per-voucher rate from `GstTaxDetailEntity` immediately; the invented 10/20/60/10 slab split replaced by a real `GROUP BY` on existing data; the invented HSN tile **removed until real line items exist** rather than replaced with a better guess.

## 2.7 GSTR-1 / 3B table coverage

| Table | Status |
|---|---|
| 4A B2B | PRESENT-BUT-WRONG |
| **5A B2CL** | **ABSENT — no data class exists** |
| 7 B2CS | PRESENT-BUT-WRONG (not consolidated by rate/POS) |
| **9B CDNR** | **ABSENT** |
| 12 HSN | PRESENT-BUT-WRONG (and unfixable — see 2.4) |
| 13 Docs Issued | PRESENT-BUT-WRONG (`cancel = 0` hardcoded) |
| **Exports / SEZ / nil-rated** | **ABSENT** |
| 3B 3.1(a) | PRESENT-BUT-WRONG (no period filter, no netting) |
| 3B 4 ITC | PRESENT-BUT-WRONG (invalid enum `ALL_OTHER_ITC`; should be `OTH`) |
| **3B 5 exempt** | **ABSENT** |
| 3B 6.1 payment | PRESENT-BUT-WRONG (violates Rule 88A) |


### ✅ SOLUTION

Add the missing tables as real structures, never as plausible-looking aggregates:
- **B2CL** — needs `posStateCode` (2.3) and the ₹1,00,000 threshold as a **dated constant**, not a literal.
- **CDNR** — needs `originalVoucherId` (2.6).
- **Exempt / nil-rated / export** — needs `SupplyNature`. `Gstr3bSupDetails.osupZero`/`osupNilExmp`/`osupNongst` **already exist** and are simply never populated — a computation gap, not a schema gap.
- **Table 13 `cancel`** — needs voucher `status: { POSTED, CANCELLED }`. Cancel reverses postings but never deletes the row or frees the number, so the count becomes truthful.

**Explicitly not built, and stated as such rather than faked:** export shipping-bill/port fields for Table 6A, LUT/bond capture, and spoiled-but-never-posted invoice numbers.

## 2.8 Purchase → ITC

**Verified: zero matches** anywhere for GSTR-2B, s.16(2), eligibility, or reverse charge. Consequences:
- Every purchase is assumed **100% eligible** — a director's personal car (blocked, s.17(5)) counts the same as trading stock.
- No GSTR-2B reconciliation, no IMS, no 180-day Rule 37 clock, no s.16(4) deadline.
- Purchase returns never subtract, so ITC is overstated by the tax on any return.
- Vendor GSTIN is equally uncaptured, so 2B matching would be impossible even if built.
- `rchrg` is hardcoded `"N"`.

### ✅ SOLUTION — agreed after challenge

**Chosen: label ITC "provisional", plus two carve-outs that are genuinely knowable locally.**

*Rejected — a full local s.16(2) eligibility engine.* s.16(2)(c) ("tax actually paid by the supplier") and s.16(2)(d) ("supplier filed their return") are **facts that live inside GSTN**, not in a local ledger. An engine computing a verdict on them from local data would *fabricate* it — the same defect class as the hardcoded `rt = 18.0`.

**Build instead:**
```kotlin
enum class ItcStatus { PROVISIONAL, GSTR2B_MATCHED, GSTR2B_MISSING, BLOCKED_17_5, RCM_SELF }
// GstTaxDetailEntity += itcStatus, blockedUnderSection17_5
```
1. A **s.17(5) blocked-credit flag** at purchase entry (only the purchaser knows a vehicle was for blocked personal use).
2. **Rule 37 180-day ageing** as a warning, not a silent auto-reversal — pure date arithmetic on data the app already has. Note the reversal is **proportionate** since Notification 26/2022-CT, not full.
3. Every ITC figure carries: *"Provisional — based on your purchase entries only, not reconciled against your GSTR-2B."*

Purchase returns must net off ITC via the same CDNR mechanism as sales (2.6).

**Honest limit:** no 2B/IMS matching means bogus-invoice ITC is undetectable here. And after July 2026 the portal computes Table 4A from 2B regardless — so the framing should shift from "your claim" to "your working estimate."


---

# PART 3 — WHAT'S MISSING (structural, not bugs)

1. **Buyer/vendor GSTIN + state on the invoice** — blocks B2B classification, place of supply, and all 2B matching.
2. **Line items on every invoice** — blocks HSN Table 12 permanently.
3. **A real inventory-mode switch** in the posting engine.
4. **Service-business ledgers** — service income; Direct/Indirect Expenses instead of Purchase A/c.
5. **Conditional Trading Account** — must be hidden for service businesses.
6. **Separate Output vs Input tax ledgers.**
7. **B2CL, CDNR, exempt/nil/export models.**
8. **Credit/debit-note linkage** (`originalVoucherId`).
9. **Payment-mode selector** on Receipt/Payment.
10. **Voucher status** (POSTED/CANCELLED) — required for Table 13 cancelled-range disclosure.
11. **Period filtering** on every GST query.
12. **ITC eligibility model** — s.16(2), s.17(5) blocks, Rule 37 180-day clock, s.16(4) deadline.
13. **GSTR-2B import + IMS accept/reject.**
14. **GSTR-1A amendment path** — now the only legal way to correct 3B liability.

---

# PART 4 — PRIORITY

**Tier 0 — data-destroying, fix first**
- **C13** login wipes the business profile and installs a fake GSTIN *(new)*
- **C12** ✅ *already fixed* — `fallbackToDestructiveMigration` removed
- **C4/C5/C6** backup fakes success; export omits the ledger; restore doubles the books

**Tier 1 — every invoice is wrong today**
- **C8** exclusive GST never grossed up — ~15.25% short-collection on the default path
- **C13→GSTIN** fake GSTIN on invoices
- **C2** split cash ledger *(runtime-proven)*

**Tier 2 — the GST pipeline**
- Capture buyer GSTIN/state · always write line items · period-filter every query · fix B2B classification · add B2CL/CDNR · Rule 88A set-off · `ty = "OTH"`

**Tier 3 — inventory & business mode**
- Gate posting on `enableInventory` · service ledgers · conditional Trading Account · payment-mode selector · weighted-average cost · closing-stock posting

**Tier 4 — statements**
- **C1** Balance Sheet & P&L are another company's hardcoded books

---


## Honest bottom line

If a real business used this app for one month and pressed "export GST", they could **not** file. The numbers are wrong before aggregation (C8), the JSON would likely be rejected outright (invalid ITC enum, invented envelope), half the required tables don't exist, the period is meaningless (no date filter), the three export surfaces disagree, and ITC is undefensible on audit.

The double-entry core is genuinely sound and worth building on. Everything between that core and a filed return needs to be built or rebuilt.
