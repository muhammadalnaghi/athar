# WM033 — Shared Declared-Position Reader (read-side, WM033-shared-declared-position-reader)

## Goal
Give Today and widgets a public, Hilt-bound read-side for the **currently saved** user-declared
position documents written by `feature/plan/.../position/PositionDocument.kt` (WM031), without
touching the writer, the storage schema, or any existing DI/Gradle wiring.

## Inputs
- Storage: `SavedFinancialPlanRepository.observe(SavedFinancialPlanKind.CASH)` rows.
  Envelope: `id, kind, title, currency, payloadVersion, payloadJson, revision, createdAtEpochMillis, updatedAtEpochMillis`.
- Frozen payload (version 1, never rewritten): JSON object with exactly
  `documentType:"balance_anchor"`, `balanceAnchorVersion:1` (JSON **number**), `asOfDate`
  (ISO string), optional `note` (string ≤300), `entries[]` (≤64; each object with exactly
  `id,label,side,currency,amount,ownerScope` — all JSON **strings**; `side ∈ {ASSET,LIABILITY}`;
  `amount` must parse as `BigDecimal`; fields ≤80 chars). Nothing computed is persisted.

## Outputs
- `core/domain/model/position/DeclaredPositionSnapshot.kt` (pure Kotlin, no JSON):
  `id, title, revision, asOfDate (verbatim string), updatedAtEpochMillis, status (enum, single
  value USER_DECLARED), hasUnstructuredOwnershipLabels (any non-blank free-text ownerScope —
  never parsed into a share), entryCount, assetsByCurrency / liabilitiesByCurrency /
  netByCurrency: Map<currencyLiteral, exact decimal String>`.
- `core/domain/repo/DeclaredPositionRepository.kt`:
  `observeSnapshots(): Flow<List<DeclaredPositionSnapshot>>` — the frozen consumer contract.
- `core/data/position/` — strict bounded read adapter `DeclaredPositionPayloadReader`
  (mirrors the writer decode rules exactly; JSON stays in core-data), `DeclaredPositionRepositoryImpl`
  (`@Singleton`, maps CASH rows off `Dispatchers.Default`), same-package Hilt `@Binds` module.

## Semantics (binding)
- Per-currency aggregation only: assets/liabilities/net summed **per literal currency** with
  `BigDecimal` (exact, `toPlainString`, no scale normalization). Net exists only for currencies
  the document actually declares — absent currencies are never zero-filled.
- Sort: `asOfDate` (parsed), then `updatedAtEpochMillis`, then `id` — total, deterministic.
- Excluded (retained in storage, untouched): non-CASH rows, envelope `payloadVersion ≠ 1`,
  foreign `documentType`, wrong/quoted version token, missing/extra root or entry keys, bad
  side/date/amount/note type, over-bounds, malformed JSON. No counts are surfaced in v1 of this
  contract; exclusion is silent at the type level by design.

## Never (hard prohibitions)
Never sum across multiple saved documents (they overlap), convert currency, add historical cash
flow, label this verified whole wealth/liquidity (status is and stays `USER_DECLARED`), parse
free-text ownership into a share, or treat missing data as zero. No writes, no migrations.

## Acceptance
1. Focused JVM tests in `core/data/src/test/kotlin/com/athar/core/data/position/` pass via the
   shared lease gate (`:core:data:testDebugUnitTest --tests 'com.athar.core.data.position.*'`).
2. Reader reproduces the writer codec's accept/reject decisions on the frozen v1 format and
   preserves every field value verbatim.
3. Existing files outside `core/domain/model/position/**`, `core/domain/repo/DeclaredPositionRepository.kt`,
   `core/data/position/**`, and this spec are unmodified.
