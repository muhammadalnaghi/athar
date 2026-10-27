# WM042 — Portfolio-return facade consolidation onto reviewed performance engines

## Goal

`calc.portfolio.PortfolioReturnsCalc` is the production facade consumed by the plan
workbench (`PortfolioWorkbench.kt`), but it is a separate legacy implementation whose
XIRR grid scan takes the FIRST sign-change bracket and returns `Available` — silently
discarding multiple-root / non-conventional uniqueness uncertainty — and whose
`timeWeighted` links same-day dated valuations around external flows as if they were
actual before/after-flow boundary observations. This ticket consolidates the facade
onto the already-reviewed `calc.performance` engines (`XirrCalc`, `TwrInput`/`TwrCalc`
contract in `model/performance/PerformanceModels.kt`), fail-closed: when the user's
dated document cannot certify a single reliable rate or certified TWR boundaries, the
facade returns a typed `Undefined` — never a picked root, never a fabricated
observation. No new math is created: no NPV, root scans, or TWR chaining live in this
facade after the change.

## Public API (unchanged, compatibility-locked)

- `PortfolioReturnsCalc.xirr(ledger, quotes, asOf): XirrResult`
- `PortfolioReturnsCalc.timeWeighted(ledger, quotes, asOf): TwrResult`
- `XirrResult.Available(annualRatePercent)/Undefined(reason, unpricedSecurityIds)`
- `TwrResult.Available(totalPercent, periodCount)/Undefined(reason, unpricedSecurityIds)`
- `ReturnUndefinedReason` tokens: `MISSING_BOUNDARY_VALUATION`, `NO_DATED_FLOWS`,
  `NON_POSITIVE_PERIOD_BASE`, `NO_CONVERGENCE` — no enum added or removed.
- Documentation-only broadening: `NO_CONVERGENCE` means "no reliable single rate could
  be certified" (bounded-search miss OR non-unique/multiple-root ambiguity);
  `MISSING_BOUNDARY_VALUATION` additionally covers TWR boundary observations the
  document cannot certify (not only missing quotes).

## New code

- `PortfolioReturns042XirrBridge.kt` (same package): maps ledger → `XirrInput`
  (`DatedCashFlow`/`DatedValuation`) and engine results → legacy result types.
- `PortfolioReturnsCalc042ConsolidationTest.kt` (test source set).
- No other production file is edited; UI, accounting, models, persistence untouched.

## XIRR mapping (parent-reviewed evidence contract)

Flows: investor contributions are negative, withdrawals and external-account dividends
positive, on their actual event dates ≤ asOf, keyed by stable eventId. The terminal
as-of valuation is the closing state; genuine zero is known, while partial valuation
is undefined with its actual unpriced IDs. Percent conversion remains ratio ×100 at
8dp HALF_EVEN, BigDecimal only.

Parent review found an additional capital/date bug in the producer bridge: nonzero
opening cash was ignored whenever an external flow existed, or assigned the first
trade date when none existed. Neither is certified by the current document, which
stores an opening amount but NO opening date. Such a balance must yield typed
MISSING_BOUNDARY_VALUATION instead of dropping capital or inventing a start date.
The no-opening-balance path uses only the user's actually dated flows and reviewed
engine. All nine producer testcase identities remain; two parent production-facade
regressions must first fail against the producer bridge, then pass after this guard.
No stored amounts, gain/holding/basis calculations or input documents are changed.

Engine-result mapping: `Rate(ratio)` → `Available`; `Ambiguous` → `Undefined(NO_CONVERGENCE)`
(honest: no single certified rate — the first-root bug class); `Rejected(EMPTY_INPUT)`
→ `Undefined(NO_DATED_FLOWS)`; every other rejection (`NO_SIGN_CHANGE`,
`ZERO_TIMESPAN`, `CURRENCY_MISMATCH`, `NEGATIVE_VALUATION`, `ZERO_AMOUNT`,
`SOLVER_FAILED`) → `Undefined(NO_CONVERGENCE)`.

## Documented divergences from legacy (honesty fixes, no prior facade tests exist)

1. Non-conventional series (≥2 chronological sign changes): legacy `Available(first root)`
   → `Undefined(NO_CONVERGENCE)`.
2. All-same-sign series after folding the (possibly zero) terminal: legacy
   `NO_DATED_FLOWS` (false: dated flows exist) → `Undefined(NO_CONVERGENCE)`.
3. Identical-date seed/terminal or exact-zero-NPV degenerate cases: legacy could return
   `Available` for a junk first grid rate; engine classifies (`ZERO_TIMESPAN` /
   `EVERY_RATE_IS_A_ROOT`) → `Undefined(NO_CONVERGENCE)`.

## TWR: fail-closed typed unavailability

`ExternalFlowBoundary` requires actual observed `valueBefore`/`valueAfter` with
verifiable `valueAfter = valueBefore + externalAmount`. The ledger document carries
only daily dated quotes; same-day accounting valuations around a flow cannot be split
into certified before/after observations, and synthesizing them is prohibited.
Therefore: any nonzero net external flow boundary → `Undefined(MISSING_BOUNDARY_VALUATION)`;
`TwrCalc` is deliberately NOT invoked with reconstructed boundaries. No nonzero
boundaries (opening cash only or nothing): `Undefined(NO_DATED_FLOWS)` — literal and
honest (the document does not date opening cash, so no certified dated opening
observation exists; legacy's undated `openingCash → asOf` linkage is a divergence,
documented here). `NON_POSITIVE_PERIOD_BASE` remains a live token via the engine for
future certified-boundary callers; the facade no longer reaches it.

## Preserved invariants

Literal currency (never converted or relabeled; single-currency enforced upstream by
`PortfolioLedger`), exact BigDecimal money, known-zero quotes valued (not missing),
dated-flow sign conventions, financial totals/holdings/cost basis, ledger input
persistence. Prior suites keep all names; no prior facade tests existed (verified).

## Acceptance

1. New regression suite proves assertion RED on the old implementation (non-conventional
   XIRR availability claim; both TWR honesty paths) — assertion failures, not compile/
   compute errors — then GREEN after consolidation.
2. Conventional-return, literal-currency (USD ledger), no-dated-input, genuine-zero
   (priced-at-zero and total-loss) and missing-boundary cases covered with independent
   numeric oracles (computed with Python `decimal`, not from implementation output).
3. Focused `:core:domain:test` green, offline, one worker, 2 GB heap, in-process Kotlin.
