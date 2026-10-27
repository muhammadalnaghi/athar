# WM033 — Bills end-to-end: explicit manual commitment + exact evidence drilldown

## Goal

Close the Bills product journey on the Plan tab. Two user-visible additions, reusing the
existing matching/scheduling engines untouched:

1. **Manual commitment input path** — an explicit, local editor that creates one
   `RecurringRule` through the real `RecurringRuleRepository.upsert`. Minimal required
   fields: name, amount, currency, cadence, next date. Account and category are optional
   and never mandatory (blank account resolves to `MANUAL_ACCOUNT_ID`, mirroring the
   suggestion-creation path). Nothing is created automatically and no charge is
   synthesized — the rule only produces expected occurrences and PENDING tray entries
   through the existing materializer.
2. **Exact supporting-record drilldown** — a PAID bill opens the exact matching stored
   transaction's record: its date, amount, currency, account id, and merchant. The record
   is the matcher's own bound transaction object (no recent-N cap, no synthesized rows).
   Close/Back returns to the same list state — scope is never reset.

## Safety contracts preserved (must not regress)

- Bills reads ALL transactions; confirmed + non-reconciliation evidence filter unchanged.
- One-to-one `resolveObservedMatches` over the full guard universe unchanged.
- Coverage-aware occurrence projection unchanged; truncation never implies a missed bill.
- Exact 30-day, currency-partitioned outgoing totals unchanged.
- `UNCONFIRMED` stays an explicit missing-observation review state — never "overdue",
  never debt. A missing category backlog is not debt and is not shown as any.
- No money `Float`/`Double` anywhere; `BigDecimal`/`Money` only.
- Existing `BillItem.evidenceTransactionId`/`evidenceDate` and all current test identities
  keep their meaning.

## Legacy amount persistence: refuse, don't truncate

`RecurringRuleEntity` persists `amountMinor: Long` with scale-2 semantics
(`Money.ofMinor(minor, currency)` on read). An entered amount whose meaningful scale
exceeds 2 fractional digits (e.g. `12.345`) cannot round-trip without truncation. The
manual save path therefore **refuses** it with an explicit `AMOUNT_PRECISION_UNSUPPORTED`
validation error before any repository call. Validation runs synchronously in the
ViewModel; the draft is kept so the user can correct it.

## Save/cancel reliability

- **Synchronous duplicate-save protection:** an in-flight save guard is checked and set
  synchronously in the event handler, before any coroutine launch. A duplicate submit
  while a save is in flight is a no-op; the UI also disables the save button while saving.
- **Error retry/cancel:** a storage failure surfaces `Failed` in the editor with the draft
  intact; the user can retry the same draft (re-submit) or cancel (dismiss discards).
  Validation failures never launch I/O and never clear the draft.
- **Success:** editor closes; the rule appears via `observeAll()` — no local shadow copy.

## State pipeline

- `BillsState.manualRuleEditor: ManualRuleEditorState?` — null = sheet closed. Holds the
  draft, an editing/saving status, and a typed failure reason.
- `BillsState.items[].evidence: BillEvidenceRecord?` — exact record for PAID items.
- New events: `OpenManualRuleEditor`, `DismissManualRuleEditor`,
  `UpdateManualRuleDraft`, `SubmitManualRule` (also the retry path after failure).
- Validation is a pure function (`bills/BillsManualRuleValidation.kt`) unit-tested directly
  and through the ViewModel.

## Inputs / outputs / edges

Inputs: user-typed draft fields. Outputs: one `RecurringRule` upsert or a typed refusal.
Edges: blank name; unparseable amount; zero/negative amount; >2dp precision; non-3-letter
currency; unparseable date; repository throwing; double-tap save; close during failure.
Anchor fields derive from the chosen next date (dayOfMonth / dayOfWeek / monthOfYear by
cadence) exactly as `canonicalScheduleRule` expects explicit fields.

## Acceptance

1. Add-commitment control visible on Bills; editor opens with display currency seeded.
2. `12.345` SAR is refused with the precision error; no rule row is written.
3. Valid draft saves exactly once under double submit; rule appears in the list.
4. Injected repository failure keeps the draft; retry succeeds; cancel discards.
5. PAID row drilldown shows the exact transaction's date/amount/currency/account; closing
   returns to the unchanged list.
6. `UNCONFIRMED` rows carry no evidence record and no debt language.
7. `:feature:plan:testDebugUnitTest --tests 'com.athar.feature.plan.*Bills*'` green,
   including all pre-existing test identities.
