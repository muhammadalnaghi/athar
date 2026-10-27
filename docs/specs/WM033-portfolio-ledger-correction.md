# WM033 — Portfolio ledger correction (in-place event edit)

## Goal
A saved dated-securities event (BUY/SELL/CONTRIBUTION/WITHDRAWAL/FEE/CASH_DIVIDEND/REINVESTMENT)
can be corrected **in place** through an explicitly prefilled form. No delete+recreate: the event
keeps its stable `eventId` and its list position; engines replay the whole corrected document
before anything is written; persistence happens only on explicit save, fenced by the session
revision (CAS). Stale revisions are refused, never overwrite.

## Inputs
- Stored `PORTFOLIO` document (decoded by the strict `PortfolioPlanCodec`), opened workbench.
- Existing reviewed engines: `PortfolioWorkbench.build` (ledger → accounting → allocation/XIRR/TWR),
  `encodeLoadablePortfolio` (encode/decode strict equality guard), `SavedFinancialPlanRepository.save(plan, expectedRevision)`.

## Outputs
- New event `InvestmentsEvent.UpdatePortfolioEntry(planId, eventId, draft)`.
- **Review seam (finish3):** `InvestmentsEvent.PreviewPortfolioEntry(planId, eventId, draft)` →
  `PortfolioEntryPreviewUi` on `PortfolioOpen.entryPreview` (exact engine-recomputed before/after
  cash / priced holdings / total, literal currency carried separately) → explicit
  `ConfirmPortfolioEntryPreview` (final CAS save bound to the captured plan/event/revision/document)
  or `CancelPortfolioEntryPreview` (zero writes, reviewed draft handed back via
  `PortfolioOpen.restoredEntryDraft` so the editor reopens with the input preserved). The edit
  sheet's save button dispatches the preview; nothing is ever written by the sheet itself.
- `PortfolioEntrySheet(initial = …)` prefilled with the exact stored literals: date, security,
  quantity, unit price, fee, amount, destination and note, exactly as encoded (no reformatting,
  no rounding, no invented defaults).
- Per-row "تعديل القيد / Edit entry" action in the entry ledger, next to delete.
- On save: same `eventId` at the same index; whole document re-validated by the engines; strict
  encode/decode equality required; save with `expectedRevision` from the session fence.

## Rules
- **Identity:** `eventId` and list order never change; only the user-typed fields change. Editing
  the date keeps the same id/position (ledger ordering is the engine's concern; ambiguous orderings
  are refused by the engine, not re-ordered here).
- **Exact source values:** untouched fields persist their exact stored strings — date, quantity,
  unit price, amount and securityId (never trimmed) exactly like fee/note: an unchanged literal on
  an unchanged kind is kept byte-identical (cosmetic zeros included); only genuinely changed fields
  go through the parsers, so validation never weakens. A blank fee/note field on an unchanged kind
  keeps the stored value verbatim (absent stays absent). Typed values replace the literal exactly.
  Notes are shown for every kind in edit mode so imported annotations can never be silently dropped.
- **Review gate before write (finish3):** the corrected document is replayed by
  `PortfolioWorkbench.build` twice (before/after), entirely off Main in the ViewModel — never in a
  composable. The preview displays the exact engine totals; a rejected correction surfaces its safe
  token and installs no preview. Confirm consumes the review exactly once (double-save is a no-op),
  refuses a stale/superseded preview as `CONFLICT` without writing, and saves the CAPTURED document
  under the captured revision CAS — it never re-reads or silently rebuilds against newer money; the
  repository CAS stays the final guard.
- **CAS:** save uses the captured session revision; `SavedFinancialPlanConflictException` →
  `InvestmentWriteProblem.CONFLICT`, stored document untouched.
- **Honest limitation:** there is no silent fallback. Unknown security/missing required field →
  `INVALID_INPUT`; event id no longer present → `INVALID_INPUT`; unsupported/malformed document →
  `UNSUPPORTED_DOCUMENT` (retained, unloadable). Every transaction kind is editable through the same
  validated path; no kind is pretended saved when it was refused.
- Add/delete/import flows, the hardened file reader, all engines, schema, and the 300 Plan testcase
  identities are untouched.

## Edges
- Cancel (dismiss or no save event): zero writes; payload byte-identical; `saveRequests` unchanged.
- Kind switch: fields remap to the new kind's shape; required blanks → `INVALID_INPUT`; note/fee of a
  different original kind are not carried over silently (they are visible and user-controlled).
- Foreign-currency or future-version documents never open the workbench, so never expose edit.
- REINVESTMENT amount must equal quantity × unit price; the engine refuses otherwise (explicit token).

## Acceptance
1. Edit roundtrip: changed quantity persists with the same `eventId`/order and exact untouched
   literals (`"20.000"`, `"1.00"`, notes); reopen through close/open reproduces the corrected inputs
   verbatim (strict codec equality) and the recomputed engines show the corrected cash/holdings.
2. Prefill equality: `editDraftOf(event)` carries every stored literal exactly; dismissing without
   saving writes nothing.
3. Conflict: external revision bump → edit refused (`CONFLICT`), newer external edit survives.
4. Invalid corrections (unknown security, engine-rejected quantity, missing amount after kind switch)
   are refused before any write with the exact existing problem tokens.
5. Focused JVM tests pass: `:feature:plan:testDebugUnitTest --tests "com.athar.feature.plan.portfolio.*"`.
6. Preview seam (finish3): the preview shows exact engine before/after cash/holdings/total before any
   write; explicit confirm saves exactly once; cancel writes nothing and the reviewed input comes back
   intact; a preview superseded by an external write is refused as `CONFLICT`; untouched cosmetic
   literals (`"007.500"`, `"020.000"`) and exact ids survive both the preview seam and the legacy path.
