# Bill-Shill — Adversarial Verification of the Audit

**Date:** 12 August 2026
**Purpose:** Independently re-test every finding in `AUDIT_REPORT.md` by trying to **disprove** it.
**Method:** 7 verifier agents, each briefed to default to INVALID and concede only when the code left no escape — the opposite brief to the agents that produced the original audit. No verifier saw the others' work. Statutory claims re-checked against government/Tally sources. Two findings additionally reproduced at runtime on the emulator against the live database.

---

## Headline result

**Nothing was overturned. 0 of 61 findings were INVALID.**

| Outcome | Count |
|---|---|
| **VALID** — upheld exactly as written | 55 |
| **OVERSTATED** — real, but narrower than I described | 4 |
| **ESCALATED** — worse than I described | 1 (plus 5 findings worse in detail) |
| **INVALID** — not a bug | **0** |
| **NEW issues found during verification** | **14** |

Revised totals: **10 Critical · 20 High · 13 Medium · 3 Low**, plus 16 honest-labelling issues and 14 new findings.

---

## Corrections against my own report

These are the places the verifiers caught me. All are recorded here rather than quietly edited.

### Downgraded (4)

**H1 — shared CGST/SGST/IGST ledger → OVERSTATED. High ⟶ Medium.**
I implied this could affect GST filing. It cannot. `getGstSummaryFlow` (`AccountingDao.kt:190-202`) computes output and input tax **independently** from `gst_tax_details` joined on `voucherType`, never reading the ledger balance, and that is what feeds GSTR-1/3B. The defect is confined to the Trial Balance / ledger-register presentation, where one net figure appears instead of separate Output Tax Payable and ITC lines. Netting in a single "Duties & Taxes" ledger is also defensible simplified-bookkeeping practice.

**H7 — voucher-number duplication → OVERSTATED.**
The TOCTOU race is real (read-then-write with no `@Transaction`, no unique index on `voucherNo`, and no debounce on the Save button — a double-tap genuinely races). But my headline example was wrong: the count-based fallback (`COUNT + 1001`) is effectively **unreachable**, because `DatabaseSeedEngine` seeds all 8 `VoucherTypeConfigEntity` rows on first launch and nothing ever deletes them. The "delete a middle voucher → duplicate number" scenario does not occur in normal operation.

**M8 — "three GstCalculatorModal implementations" → OVERSTATED.**
There are **two** functions named `GstCalculatorModal` (`VouchersScreen.kt:1372` and `ui/components/GstCalculatorModal.kt:35`), not three. Counting `GstItemInputField` as a third *GST widget* is defensible; as literally written, my claim was wrong. Everything else in M8 stands, including that the official modal's `onApplyToVoucher` discards all four of its arguments.

**M10 — "the Receipt form has no invoice-number field" → OVERSTATED.**
Wrong. `VouchersScreen.kt:629-648` renders **Narration and Tags** for every voucher type including RECEIPT, and those are exactly the two fields the matcher searches (`receipt.narration.contains(invNo) || receipt.tags.contains(invNo)`). There is no *dedicated* invoice-number field or autofill, but the substrate exists. The fragile exact-string party matching stands.

### Escalated (1 severity change + 5 worse-in-detail)

**L3 — stale `groupName`/`category` → ESCALATED. Low ⟶ High.**
I called this cosmetic. It is not. Verified independently: `createLedger` (`AccountingRepository.kt:147-173`) constructs `LedgerEntity` **without setting `groupName` or `category` at all**, so every manually-created ledger silently takes the entity defaults `"General Ledgers"` / `EXPENSE` (`AccountingModels.kt:111-112`) regardless of the group the user chose. `LedgerManagementModal` displays those stale values *and pre-fills the Edit dialog from them* — so editing **any** field on a ledger (even just its opening balance) re-submits "General Ledgers", and `updateLedgerDetails` then reassigns `groupId` to it. That corrupts the real, joined `ledger_groups` categorisation that every report depends on. Reports are affected, not just the UI.

**C1** — the address `"Sarafa Bazar, Lashkar, Gwalior"` is **unconditional** (`HierarchicalFinancialStatement.kt:96`), not an `.ifBlank` fallback like the name and GSTIN. It prints for every business even when their own details are correctly configured.

**C5** — the export omits **9 of 13** entities, not 5 of 11. I miscounted `AppDatabase`'s entity list (it includes `CrashLog` and `MonthlyArchive`). Also newly confirmed: `createCustomVoucher` stores both ledger names as `"Debit / Credit"` in `partyName`, so re-import fabricates a single bogus ledger with a slash in its name.

**H2** — debiting the vendor's own payable ledger is **structurally guaranteed by call order**, not conditional on the vendor already existing. `getOrCreatePartyLedger` runs at line 270 before the `when` block and creates the vendor under Sundry Creditors; the asset branch then calls `getLedgerByNameOrCreate(partyName, "Fixed Assets", ASSET)`, which matches **globally by name** and returns the existing row while silently discarding the requested group. Same row, both legs.

**H9** — worse than reported. See NEW-8: importing a Tally credit note doesn't merely drop it, it posts it as a positive sale.

**X10** — worse than reported. `TelemetryEngine.recordQueryLatency()` has **zero call sites anywhere in the app**. The real latency log isn't ignored; it can never contain data.

---

## Runtime proof obtained during verification

Two findings were escalated from "traced in code" to "reproduced on a running device".

### C2 — the split cash ledger, proven end-to-end

Baseline (live DB): 12 ledgers, 12 groups, no `Cash-in-hand`.
I then posted **one ordinary ₹7,000 Contra** through the app's normal UI.

```
ledgers after:  13 | Cash-in-hand | Cash-in-hand | 0.0     <- new ledger AND new group
journal lines:  CONTRA | HDFC Bank Ltd | 7000.0 | 0.0
                CONTRA | Cash-in-hand  |    0.0 | 7000.0   <- posted to the empty duplicate
```

Resulting Trial Balance, as displayed to the user:

| Ledger | Group | Allocations (Dr) | Sources (Cr) |
|---|---|---|---|
| `Cash in Hand` | Cash-in-Hand | 50,000.00 | – |
| `Cash-in-hand` | Cash-in-hand | – | **7,000.00** |

The user's real cash ledger shows **no movement at all**, and the duplicate carries a **credit balance on a cash account** — an accounting impossibility. The Dashboard's ₹43,000 aggregate is correct only by accident, because `LIKE '%Cash%'` happens to sum both.

One nuance the verifier added: only the *first* Receipt/Payment/Contra creates the duplicate; later ones reuse it. My wording ("every one creates a second ledger") should read "the first one does, permanently."

Root cause is narrower and more fixable than three separate bugs: **`getLedgerByNameOrCreate` looks up by name globally and silently ignores the caller's requested group and category.** That single function underlies C2, H2 and H4.

### C8 — the GST short-payment, re-derived independently

The verifier recomputed the arithmetic from source and confirmed `finalTotalAmount` (`VouchersScreen.kt:184`) is **written once and never read anywhere** — dead code. It also closed every escape route I had not checked: the in-form GST calculator's "Apply to Voucher" writes back the *base* figure, `QuickEntryBottomSheet` is entirely dead code, and the Dashboard calculator discards its results. **There is no path in the app that produces a correct exclusive-GST invoice.**

---

## New issues found during verification (14)

Ordered by severity.

| # | Severity | Issue |
|---|---|---|
| NEW-1 | **Critical** | **Inventory stock is silently zeroed on restore.** `createInventoryItem` hardcodes `stockQty = 0.0`, and the import loop never reads the exported `stockQty`. Combined with the no-clear-first duplication (C6), restoring a backup both duplicates every item and resets all stock to zero. |
| NEW-2 | **High** | **Tally/Marg XML import turns credit notes into sales.** `TallyMargXmlUtil.kt:132-140` matches `contains("SALE")` first, so Tally's `"Sales Return"` becomes `VoucherType.SALES` and `"Purchase Return"` becomes `PURCHASE`. Verified: the file produces `SALES_RETURN`/`PURCHASE_RETURN` **zero** times. Importing a credit note *increases* output tax liability instead of reducing it. |
| NEW-3 | **High** | **GSTR-3B CSV violates the mandatory ITC set-off order.** `CsvExporter.kt:60-64` nets each tax head independently (`outputCgst - inputCgst`, etc.). Rule 88A / s.49A-49B require IGST credit to be exhausted first, and CGST credit may never offset SGST liability. Any business with interstate or import purchases gets a wrong "Net Tax Payable". |
| NEW-4 | **High** | **Two disagreeing B2B/interstate classifiers.** The JSON export uses the party's real GSTIN (sound); the CSV/PDF path uses the broken `user.gstin.isNotBlank()` heuristic and the manually-toggled `isInterstate` flag. The app can bucket the same invoice differently in its two "GSTR-1" outputs. |
| NEW-5 | **High** | **"Undo delete voucher" corrupts GST.** `restoreVoucher` (`AccountingViewModel.kt:477-489`) guesses `gstRate = if (gstAmount > 0) 18.0 else 0.0` and always passes `isInterstate = false`, discarding the original rate and interstate status — even though the codebase already knows how to derive the rate. |
| NEW-6 | **Medium** | **Dashboard "Easy Entry" hardcodes 18% GST on every Payment/Receipt** (`DashboardScreen.kt:1456-1463`), guaranteeing the spurious `GstTaxDetailEntity` rows described in M4 fire on ordinary cash transactions. |
| NEW-7 | **Medium** | **GST Calculator → Easy Entry loses everything.** The hand-off discards all four computed values and opens a blank form, forcing manual re-entry. |
| NEW-8 | **Medium** | **Every CONTRA creates a junk party ledger.** `getOrCreatePartyLedger` runs for all voucher types before the `when` block, but the CONTRA branch never uses it — so each contra permanently adds a dead "Sundry Debtors" ledger named after its free-text description, polluting the ledger list and party autocomplete. |
| NEW-9 | **Medium** | **A third divergent cash-ledger group name.** `createCustomVoucher` uses `"Cash/Bank Accounts"`, alongside the seed's `"Cash-in-Hand"` and the repository's `"Cash-in-hand"`. Whichever path runs first wins the group association. |
| NEW-10 | **Medium** | **Balance-sheet drill-down shows unrelated vouchers as a "breakdown".** `onSelectLedger` filters by the *hardcoded fake* ledger names from C1, matches nothing, then falls back to `vouchers.take(5)` — presenting five arbitrary vouchers as the breakdown of a fabricated line item. |
| NEW-11 | **Medium** | **Ledger group is free text, not a picker.** `LedgerManagementModal.kt:361` is a plain text field despite a `commonGroups` list existing right above it. With L3's stale pre-fill, this fragments `ledger_groups` on any typo. |
| NEW-12 | **Low** | **`QuickEntryBottomSheet` is entirely dead code** — a fully-built composable with its own contra-account prediction wiring, never instantiated. |
| NEW-13 | **Low** | **Login accepts non-numeric input.** Validation checks only `.length`, never digits — phone `"aaaaaaaaaa"` with OTP `"abcd"` logs in. |
| NEW-14 | **Low** | **`finalTotalAmount` is dead code** (`VouchersScreen.kt:184`) — direct evidence the correct total was computed and then discarded. |

---

## Confirmed correct (re-tested, no flaw found)

The verifiers actively tried to break these and could not:

- **Double-entry arithmetic** — all 8 voucher types balance exactly, including zero-GST, interstate and inventory paths. CGST/SGST `/2.0` is lossless in IEEE-754.
- **GST filing totals** — `getGstSummaryFlow` separates output and input tax correctly and independently of the shared ledger (this is what rescued H1 from Critical).
- **Trial Balance** — genuine SQL; correct DR/CR opening-balance handling.
- **The dedicated Contra modal** — always produces safe fixed strings; the inversion in M9 is only reachable via free-text entry.
- **The 12-hour reconciliation worker** and the 24-hour backup/GST-export periodic jobs — genuinely implemented as described in the UI.
- **`createCustomVoucher`** — a correct generic two-ledger poster.
- **The Analytics & Trends charts rebuilt earlier today** — re-reviewed a second time (sign conventions, month zero-fill, `'localtime'` bucketing, running-sum window boundaries). No flaw found in either pass.

---

## What this means

The audit stands. Every Critical finding survived an agent whose explicit job was to kill it, and the two most consequential — the split cash ledger and the GST short-payment — now have runtime and arithmetic proof respectively.

The corrections that matter for planning:
- **H1 drops out of the urgent tier** — GST filing is not affected by the shared tax ledger.
- **L3 moves into it** — silent ledger re-categorisation on edit corrupts real reports.
- **One function, `getLedgerByNameOrCreate`, is the shared root cause of C2, H2 and H4** — fixing its group/category handling addresses three findings at once.
- **NEW-1 belongs with the backup work** — restore doesn't just duplicate data, it zeroes all stock.
