# WM040 — Portfolio performance evidence

## Goal
On the opened-portfolio result surface, make the dated scope of every performance figure
immediately visible, and offer an optional, collapsed-by-default explanation of the existing
gain/return results together with the stored dated inputs they are derived from. No new
calculators, no imported history, no live-broker claims.

## Inputs (all already stored in the opened document)
- `bench.document.events` (typed `EventInput` rows: trades, contributions, withdrawals, fees,
  cash dividends, reinvestments — each with an ISO date literal),
- `bench.document.quotes` (dated saved quotes),
- `bench.asOf` (the replay boundary already shown in the header),
- the existing engine outputs: `bench.xirr`, `bench.twr`, `accounting.moneyGain` including
  their honest undefined/unavailable states (`ReturnUndefinedReason`, `unpricedSecurityIds`,
  `MoneyGain.unavailableBecause`).

## Outputs
1. `PortfolioPerformanceBlock` gains a one-line dated-scope caption bound to the accounting
   as-of date, so the results carry their own time boundary without scrolling to the header.
2. New `PortfolioPerformanceEvidence040.kt`:
   - a pure, unit-testable model built only from the inputs above (input counts by kind,
     earliest→latest dated span, quote count, flow state of each result);
   - a progressive-disclosure section (48dp bordered chip, collapsed by default) that, when
     opened, states in plain Arabic-first copy: results are recomputed from the saved dated
     inputs at every open with no external prices or estimates; what "money gain" is (an
     accounting figure, not an annualized return); what XIRR and TWR require; and that
     undefined results mean missing dated inputs, never substituted values. Evidence lines
     show counts and the dated span — never new money math.
3. Bilingual strings `values/wm040_strings.xml` (Arabic default) and `values-en/wm040_strings.xml`.
4. `PortfolioPerformanceEvidence040Test.kt` covering span/count derivation, empty-input
   honesty, and undefined/unavailable state propagation.

## Edges
- Empty workbench (no events/quotes): the section still renders the honest "no dated inputs"
  state; nothing is fabricated.
- Undefined XIRR/TWR with `MISSING_BOUNDARY_VALUATION`: the evidence section names unpriced
  securities as stored-input context (same ids the engine reported).
- `moneyGain.amount == null`: `unavailableBecause` ids are surfaced, not hidden.

## Acceptance
- Focused `:feature:plan:testDebugUnitTest` passes for the new test class; `compileDebugKotlin`
  compiles the touched module.
- No changes to callbacks, review→save flow, allocation controls, import, cancel, Plan001,
  or any `core:*`/codec/ViewModel file. No money arithmetic in Compose; amounts render only
  through the existing BigDecimal `Money` formatter.
