# WM028-fast — Trends: independent periods, target-vs-actual, filter/reset/back/detail completion

## Goal
Finish the remaining Trends UX/acceptance gaps **on top of the accepted drilldown patch**
(6×48dp zero-inclusive comparison cells, both-population category evidence, frozen Other
membership, month∩bucket drilldowns, request-aware window headers, absolute signed deltas —
all preserved verbatim). Only gaps visible in source are addressed.

## Gaps found in source (not covered by the accepted patch or existing tests)
1. **No back/close affordance in the evidence sheet.** Drilling bucket → month-in-bucket
   *replaces* the request; the only exits are the scrim/back gesture, which discards the
   whole drill context. The sheet also has no explicit close control.
2. **Target-vs-actual variance math is UI-inline** (`average − target` inside
   `MonthlyPanel`), untestable, and the label "فرق" does not say what is compared.
3. **`ToggleSourceFilter` / `SetTypeFilter` have no fixture-grounded VM tests** where
   dropping the filter changes expected IDs/amounts (account/category/merchant do).
4. **`ClearFilters` preservation of the independent comparison window and currency
   override is untested** (only period preservation is).
5. **Detail-over-evidence back protection and stale async query convergence are untested**
   (flatMapLatest cancellation, `OpenEvidence` clearing a stale open detail, detail close
   returning to the same evidence), and **MonthBar evidence ↔ dashboard bar
   reconciliation is untested**.
6. Transient sheet visibility (`showCustomRange`/`showFilters`) resets on config change.

## Changes (lane files only: `feature/trends/**`, new `fast_trends_strings.xml`)
- `TrendsViewModel`: evidence request **stack**. `OpenEvidence` pushes (no duplicate of the
  top), `CloseEvidence` clears all (semantics unchanged), new `TrendsEvent.BackEvidence`
  pops one level, else closes. State gains `evidenceCanGoBack`.
- `TrendsScreen`: evidence header gains a back control (stack > 1) and explicit close
  control; `rememberSaveable` for sheet visibility flags. `MonthlyPanel` renders a
  pre-computed variance; label states average-vs-target.
- `MonthlySeries` (feature-local): pure `income/expense/savingsTargetVariance`
  (`average − target`, null when no target) — exact BigDecimal, no Float/Double.
- New strings **only** in `fast_trends_strings.xml` (values + values-en): evidence back,
  clarified variance label. No edits to shared/common resources.
- Tests: `TrendsViewModelQueryTest` additions (source/type filters changing IDs/amounts,
  clear preserving comparison+currency, detail/back/evidence protection, stale async
  convergence to the last query, month-bar reconciliation); new pure
  `TrendsTargetVarianceTest` (exact averages, exact variances, currency-mismatched target
  excluded). No assertion is relaxed; RED first for behavior repairs.

## Non-goals / frozen
Drilldown patch logic, aggregate identities, 48-Trends/40DS legacy identities, DS charts
and tokens, domain contracts, schema, navigation, other features. Plan-owned
`savingsRateTargetPercent` is NOT wired into Trends. No ADB/Maestro execution (parent runs
the authored flow `build/qa/wealth-028/fast-trends-filters-evidence-back-flow.yaml`).

## Acceptance
`python …/athar_fast_gate.py trends <attempt> -- :feature:trends:testDebugUnitTest` green,
all new assertions fixture-grounded; handoff JSON+MD persisted with exact counts.
Back and Close reserve at least 48dp on **both axes**, including clickable bounds.
The stale-query regression must subscribe a delayed older repository flow before
selecting the new period, observe cancellation, then release the old result without
changing the latest totals. Type-filter coverage must assert retained income IDs and
amounts, cleared-filter evidence, and explicitly opened empty transfer evidence.
