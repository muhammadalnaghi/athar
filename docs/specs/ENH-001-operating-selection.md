# ENH-001 — Unified operating income and expense selection

## Goal

Today, Trends and the home-screen widgets must select the same confirmed,
non-reconciliation operating rows, so a bank transfer can never appear as
consumption merely because it is not income. This is the first bounded
classification correction (ENH-01 / ENH-05 / ENH-10 foundation); it does not
complete those enhancements.

## Inputs

Confirmed `Transaction` rows already read by the existing period queries
(`observeByPeriod(..., status = CONFIRMED)`). No repository, DAO or schema
change; the selector is a pure function over the rows callers already hold.

## Outputs

`core:domain` gains `OperatingTransactions(income, expenses)` and
`selectOperatingTransactions(transactions)`: rows must be `CONFIRMED` and not
reconciliations (existing `isReconciliation()` predicate: `MANUAL` source with a
`reconcile-`-prefixed `sourceRefId`), split by explicit `INCOME` / `EXPENSE`.
Transfers land in neither list. Order, currency and object values are preserved;
category and classifier confidence are ignored.

`TodayViewModel` (monthly, today, and goal-nudge derivations), `TrendsViewModel`
(current, prior, and 12-month series) and `WidgetDataLoader` (monthly and today
snapshots) consume these lists. Period queries, `Money.sumAmounts` aggregation,
feeds, pending trays, drilldown, targets and comparisons are unchanged.

## Edges

Empty input; single-type inputs; transfer-only; pending/dismissed rows of every
type; manual reconciliation income and expense; non-manual sources whose ref
merely starts with `reconcile-`; null-category / zero-confidence rows; purity
across repeated calls. Covered by `OperatingTransactionsTest` (11 cases) and two
new `TodayViewModelTest` regressions (transfer totals + feed visibility;
reconciliation neutrality), which were observed to fail on the old
partition-by-not-income behavior before the fix.

## Acceptance

Named domain and Today tests pass red→green; Trends and widgets compile against
the selector; both debug APK variants assemble; the full unit suite passes with
no new compiler warnings; `git diff --check` clean.

## Limits (unchanged by this slice)

`Money.sumAmounts` remains a raw-number no-FX projection; legacy stored kinds
may still misclassify card repayments/refunds; the Trends category drilldown
keeps its own selection contract; no economic-event schema, multicurrency
grouping, or auto-confirm policy change is introduced here.
