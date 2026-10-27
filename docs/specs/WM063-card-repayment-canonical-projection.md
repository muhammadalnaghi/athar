# WM063 — Canonical CARD_REPAYMENT projection

## Goal

Add one narrow public entry point, `CanonicalPurchaseRecorder.recordCardRepayment`, that admits an explicit POSTED `EconomicKind.CARD_REPAYMENT` event through the existing UNALLOCATED admission engine and confirms, atomically in the same Room transaction, the pipeline's freshly inserted `PENDING` row of type `TRANSFER`. The purchase path (`record`, `isPostedPurchase`, `matchesNewPurchase`, `NewPurchaseProjection`) keeps its public names and behavior compatible, and card repayments stay rejected through the old purchase API.

## Inputs

Same arguments as `record`: `graph: FinancialEventGraph`, `originalBytes: ByteArray`, `evidenceRef: String`, `projection: NewPurchaseProjection` (the type is not renamed or duplicated). Financial semantics stay owned by `FinancialRecordingPolicy`, `FinancialGraphFactory` and `UnallocatedAdmissionRepository`; this recorder adds only the expected-kind/expected-row-type seam.

## Eligibility (all must hold)

- Posting `POSTED` with `EXPLICIT_POSTED` support (no heuristic, no hold/authORIZED).
- `graph.kind == CARD_REPAYMENT`, direction `OUT`, genuine `eventDate`, `legacy == null`.
- Observation identity namespace `sms` or `notification`.
- Booked amount strictly positive, exactly one `PRINCIPAL` component, no legs, no unresolved/fee composite.

## Projection contract

The confirmed row must be the exact freshly inserted row: same `id`/`sourceRefId`/`source` (SMS or NOTIFICATION), literal `TRANSFER` type, `PENDING` status, `categorySource != "USER"`, `updatedAt == createdAt` matching `projection.createdAt`, `date == observation.eventDate`, literal currency and exact minor-unit amount. Admission only advances `status` to `CONFIRMED` and stamps `updatedAt = projection.confirmedAt`; it never rewrites type, currency, amount, date or category — a card repayment therefore never adds spend or income.

## Edges

Restore-epoch check before and during admission; idempotent replay and source-conflict outcomes never reapprove a row or override user edits; cancellation propagates; SQL/invariant failure rolls canonical and visible rows back together; oversized original bytes refuse before any write. Rows that are missing, user-edited, confirmed, dismissed, or mismatched in type/source/ref/currency/amount/date are refused without canonical writes.

## Acceptance

- New pure JVM tests over real `FinancialGraphFactory` graphs and real `TransactionEntity` rows cover: valid transfer projection, purchase API rejecting repayment, wrong type/source/ref/currency/amount/date, user-edited/confirmed/dismissed rows, heuristic/hold/missing date inputs, fee-composite and routed-leg graphs, and policy-unreachable inputs the factory already refuses.
- One focused offline `:core:data:testDebugUnitTest` run (max-workers=1, no daemon, in-process Kotlin, 2 GB heap) passes with all pre-existing tests intact.
- A small synthetic native test file documents the Room-atomic proof; the parent owns its device execution. No parser/app/domain/schema/Gradle changes.

## Ownership

Owned files: `core/data/.../ledger/api/CanonicalPurchaseRecorder.kt`, the two new synthetic test files, this spec, and `build/qa/wealth-063/handoff.json`.
