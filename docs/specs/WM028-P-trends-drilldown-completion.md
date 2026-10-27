# WM028-P — Trends Drilldown Completion

## Goal
Close the four observed drilldown interaction gaps in the isolated wealth-028 snapshot without changing any aggregate identity, filter behavior, or visual token.

## Inputs
- `TrendsAggregates` (from `aggregateTrends`) already carries the exact populations every drilldown needs: `comparisonOperating` (equal-elapsed current side), `priorOperating`, `monthlyOperating`, `expenseBreakdown` (frozen top-N + Other membership), and `windows` (comparison/current/prior windows, tail disclosure).
- Existing requests: `Headline`, `HeadlineComparison`, `Bucket`, `MonthBar`.

## Gaps → Changes
1. **Category comparison rows dispatched `Bucket`.** New `TrendsEvidenceRequest.CategoryComparison(key)` built purely from `comparisonOperating`/`priorOperating` membership; the evidence sheet renders both exact populations under their own window labels (existing `ComparisonEvidence` UI path). No requery, no approximation: totals reconcile with the delta row the user tapped.
2. **Evidence context printed `state.period` for month drilldowns.** `EvidenceContext` becomes request-aware: `MonthBar`/`MonthInBucket` print the drilled month; comparison requests print both exact windows; headline/bucket keep the top-level period.
3. **Bucket evidence monthly chart had no tap.** The sheet's `AtharMonthlyChartWithLines` now emits `MonthInBucket(month, key)` — scoped to exactly bucket ∩ month.
4. **`AtharComparisonBars` had no tap callbacks.** Backward-compatible optional `onBarClick: ((AtharComparisonSlot) -> Unit)?` (DS-local enum; DS imports no feature symbols) wired to `HeadlineComparison(metric)` per pair. Full bar cells are the ≥48dp targets, including zero bars.

## Membership contract
One frozen rule (`bucketMembership`): uncategorized = `categoryId == null`; Other = key outside the breakdown's visible top keys (frozen once from the breakdown, never recomputed month-specifically); real key = `categoryId == key`. The same rule now drives current-window rows, the 12-month trend, and month-in-bucket drilldowns.

## Edges preserved
Filters stay baked into aggregates (evidence can never exceed the filtered populations); original row currency preserved (`AtharNumber`); no money→Float/Double; palette/type scale untouched; equal-elapsed windows and unmatched-tail disclosure untouched; full evidence lists stay complete, never page-limited; `CloseEvidence`/`CloseTransactionDetail` still never mutate the query.

## Acceptance
- Pure tests in `TrendsComparisonWindowsTest` extended with independent expected IDs/money for: category comparison populations, month-in-bucket scoping, frozen Other/Uncategorized membership across months, filter preservation.
- Zero new strings, modules, dependencies, or Gradle/schema edits.
- Status: authored-not-run (no build permitted in this lane); parent executes verification.
