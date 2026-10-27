# WM028-P — Safe Bills median integration

## Goal and diagnosis

Integrate the useful Bills handoff without regressing later integration repairs. The documented detector contract is bounded variation within 10% of the median, while the current implementation anchors on the smallest amount. The worker fixes that baseline but doubles, subtracts and multiplies `Long` values without overflow protection, and repeatedly copies growing clusters. Its test-file postimage also predates the parent-upsert DAO safety stubs. Do not adopt either file wholesale.

## Inputs and outputs

Use confirmed transaction rows already supplied by the existing repository flow. Keep merchant/currency/type grouping, distinct-date cadence gates, batch rejection, nominal suggested amounts, rule suppression, account/category ambiguity, schedules, reactive locale and Plan001 unchanged. No writes, activation, automatic commitments, schema or network changes.

Walk amounts in ascending order. Test each tentative cluster against its exact median; for an even count, the clustering median is the mean of both middle values. Equality at 10% is accepted. Compute the doubled median and deviations with exact `BigDecimal`, including values near `Long.MAX_VALUE`; never perform potentially overflowing intermediate money arithmetic in `Long`. Read the two virtual middle indexes and extremes directly from the existing cluster plus candidate, avoiding a quadratic growing-list copy. Keep the existing nominal suggestion amount (observed upper-middle row), not a new averaged bill amount.

Transfer the worker's three tests by targeted insertion, retaining all existing tests and both read-only DAO stubs. Add five independent cases: exact even-median boundary, one minor unit beyond it, equal maximum values, adjacent maximum values, and extreme spread that must not become a false recurrence. The synthetic fixtures exercise the real repository output, not a Python approximation.

Apply only the suggestion-card spacing correction using the existing 4dp theme token, Money import ordering and removal of the unused test import. The current `RecurringSchedule.project()` KDoc already describes the legacy combined cap accurately, so do not rewrite it based on stale feedback.

## Acceptance and limits

Record bound prior test identities and source hashes before edits. Execute the focused repository suite against unchanged production code to retain genuine assertion RED; then implement and execute the full affected data and Plan suites plus both unseeded debug builds. Retain all prior identities and independently parse XML counts. Keep authored tests, executed tests, compilation, lint and native acceptance separate. Do not reinstall or publish merely because compilation succeeds. Native Bills/full WM02–04 acceptance remains open until actually exercised.
