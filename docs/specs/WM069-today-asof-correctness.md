# WM069 — Today as-of date correctness

## Goal

Today's actual money figures must describe the world as of the injected clock's date. A CONFIRMED transaction dated after that date is a future observation: it must not count toward the current month's actual operating totals nor appear as today's/recent actual activity — while remaining stored verbatim until its day arrives.

## Symptom

`TodayViewModel.deriveState` observes `Period.Month(currentMonth)` with status CONFIRMED. `Period.Month` is a calendar bucket whose `endExclusive` is the first day of next month, not an as-of boundary. Every confirmed row in the bucket — including rows dated after today — flows into `selectOperatingTransactions`, inflating `totalIncome`, `totalExpense` and `netFlow`; and `confirmed.partition { it.date == today }` drops future rows into `recent`.

## Root cause

Missing as-of filter between repository observation (calendar bucket) and actual-activity derivation (as-of semantics).

## Fix

In `deriveState` only: derive `today = clock.now().toLocalDateTime(...).date` first, filter confirmed rows to `date <= today`, and feed that filtered list to both the operating-totals selector and the today/recent partition. The comparison is inclusive so same-day rows — and rows whose date the clock has reached — remain actual. No constructor, API, or state-shape change.

## Explicitly preserved

- Literal money/currency semantics (`Money`/BigDecimal, SAR display filtering of foreign rows).
- Shared `selectOperatingTransactions` reconciliation/transfer exclusions.
- Pending/dismissed visibility (pending tray; `dismissedToday` exact-day filter unchanged).
- Category provenance, personal choices, and suggestion machinery.
- Existing loading behavior and `TodayState` shape.
- Goal lookback: `Period.Last(3, endingAt = today)` already ends tomorrow-exclusive and is not an established leak; untouched.

## Explicitly out of scope

No mutation, dismissal, redating or deletion of stored future observations; no changes to History, account/netWorth/balance models, goal calculators, parser/recording, DTOs/schema/backup/widgets/Gradle/dependencies/permissions/provider settings. This makes no claim about account-balance semantics or every reader's date handling (Trends already excludes actual rows after its own as-of date).

## Acceptance

New `TodayAsOf069Test` (real `TodayViewModel` public state, neighboring fake/coroutine patterns) at fixed synthetic date 2026-10-25:

1. With a current-day SAR 3.14 confirmed expense plus a SAR 5.00 confirmed expense dated 2026-10-26 inside the same month bucket: monthly actual spending excludes the future row; `todayNet` excludes it; `today`/`recent` feeds exclude it.
2. Same-day confirmed income retained; a transfer and a foreign-currency (USD) row stay visible in feeds yet excluded from operating totals.
3. The repository receives no writes (`upserts` empty): source money/records unchanged.
4. Goal nudge still derives from the separate last-3-months observation (existing contract retained).
5. Boundary: once the clock reaches a future row's date, that row counts as actual (inclusive `<=`, no over-filtering); earlier-in-month rows keep counting.

## Risks

Day boundaries follow the clock's local timezone, as before. Excluded future rows re-enter totals exactly on their own date; History and other readers are unaffected by this change.
