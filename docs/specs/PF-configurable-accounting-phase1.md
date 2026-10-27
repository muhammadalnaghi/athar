# PF — Configurable multi-account accounting (Phase 1)

## Goal
Make Athar’s personal-finance math correct for owned multi-account setups (e.g. ANB income + D360 spending) without hardcoding those banks: configurable income senders, internal-transfer matching, selective SMS import filters.

## Reuse
- Accounts + `smsSenders` routing, `TxType.TRANSFER` exclusion in `selectOperatingTransactions`, D360 templates, Settings Accounts/Categories, Room v11, KnownBankSenders.
- WIP `SmsImportFilter*` domain/prefs/service on `feat/sms-import-filters`.

## This slice
1. Pure domain: `InternalTransferMatcher`, `IncomeClassification`, `MonthlyAccounting` + required §9 example tests.
2. `IncomeSourcePreferencesRepository` (DataStore; default empty).
3. Ingest: demote non-eligible credits to `TRANSFER` / needs-review; Hilt bind `SmsImportController`.
4. Live SMS gated by import prefs (closed defaults).

## Deferred
- Settings UI for Income Sources + SMS Import screens.
- Account role columns / DB migration.
- Linked `transferGroupId` persistence.
- Dashboard wiring to `MonthlyAccounting`.
- Category label remaps / ANB-specific salary templates.

## Acceptance for this slice
- Domain tests assert Income=12000, Expenses=2800, Transfers=3000, Net=9200.
- Empty income sources ⇒ no automatic income.
- Live tracking off / no senders ⇒ live SMS not ingested.

## Phase 2 follow-up (dashboard wire)

- `selectAccountedOperating` is the single Today/Trends headline path.
- IncomeClassification: non-income senders → Needs Review (never auto internal transfer).
- Transfer pairing is recomputed in memory; no `linkedTransferGroupId` persistence yet.
