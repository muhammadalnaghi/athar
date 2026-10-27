# WM031 — Saved Dated Position (مركز مالي مؤرّخ)

## Goal

A reachable, saved, dated-position workflow: the user explicitly declares a small set of
dated asset/liability balances, sees per-currency asset/liability/net totals with honest
freshness/missing disclosures, and can explicitly save, reopen, update, cancel or delete
the input document with revision-conflict safety. This is **user-declared input**, never
verified live bank balances and never transaction postings.

## Storage decision (evidence-based)

`feature/plan/cashforecast/CashScenarioDocument.kt` already documents that the shared
`SavedFinancialPlanKind.CASH` namespace is co-owned: *"The CASH namespace is shared with
the position worker, which owns BALANCE_ANCHOR documents for user-declared dated balance
anchors."* This lane **is** the position worker, so WM031 stores its documents under
`SavedFinancialPlanKind.CASH` with `documentType = "balance_anchor"` and **adds no enum
constant** (the delegated `POSITION` constant was optional; a second namespace would
duplicate user data and contradict the documented ownership). Core/domain and core/data
are untouched. The generic guarded, versioned `SavedFinancialPlanRepository` (encrypted
Room, create-only saves, exact-revision CAS updates/deletes, structural payload guard)
is reused as-is; no schema or backup-envelope change.

## Payload (feature-owned codec, version 1)

Root object, exact keys: `documentType` (`"balance_anchor"`), `balanceAnchorVersion`
(JSON number 1), `asOfDate` (ISO `YYYY-MM-DD`), `entries` (array), optional `note`.
Each entry object, exact keys: `id`, `label`, `side` (`ASSET`|`LIABILITY`), `currency`
(literal code), `amount` (exact decimal literal string — BigDecimal, never
Float/Double), `ownerScope` (user-declared literal ownership/scope text). All monetary
and textual values are JSON strings; a quoted version number is a field-type
malformation. Decode failures classify as `Malformed`/`UnsupportedVersion`/`Foreign`
and stay retained, never deleted or repaired. A save is refused unless
`encode→decode` reproduces the document exactly.

## Totals (pure)

`PositionTotalsCalc` sums exact `BigDecimal` amounts **per literal currency only** —
no cross-currency conversion, no combined "wealth" figure, no owner-scope aggregation
(no duplicate counting, no family-portfolio totals). Disclosures: the as-of date and its
age in days, the user-declared (not verified) provenance, the no-FX rule, and retained
but unloadable documents. Unknown integration scopes are omitted, never fabricated.

## Surface

One quiet card inside the existing `InvestmentsContent` (no new bottom tab, no
`PlanScreen` edit): collapsed list of saved position documents with open/update/delete,
an inline editor (title, as-of date, note, entries), and an inline totals result.
Revision conflict on save/delete surfaces a safe constant message and refreshes the
local snapshot; the user retries explicitly.

## Tests

Pure codec round-trip/strictness, pure totals, and a ViewModel persistence suite over
the test-only `FakeSavedFinancialPlanRepository` mirroring the real conflict semantics.
