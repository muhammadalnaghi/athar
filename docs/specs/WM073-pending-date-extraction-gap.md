# WM073 — Pending explicit-posted date-extraction gap

**Goal.** Records real completed payments that were left pending only because the
stated bank date was not extracted. Reduces the review queue by exactly the rows
that carry genuine evidence; no date is ever invented.

**Inputs (actual corpus, 17 rows).**
- 9 × AlRajhi `PoS Purchase` ending in a year-first slash stamp with single-digit
  day/month (`26/7/3 19:17` = 2026-07-03 19:17). Received same day in every row.
- 6 × STC Bank `Online Purchase Transaction` with bare `Date 19/02/2026`
  (DD/MM/YYYY, no time). Received within ~1 day in every row.
- 2 × STC Pay `Online Purchase` (SAR, posted, explicit header) whose template was
  absent from the certified-purchase supported set.

**Changes.**
1. `DateExtraction.kt`: add `a/b/c HH:MM` with 1–2-digit third component, decoded
   by the existing receive-grounded `slashTwoDigit` disambiguation (year-first is
   accepted only when the day-first reading is impossible — year < 2015 — and the
   year-first reading is within 2 days of the receive date); add date-only
   `DD/MM/YYYY` mapped to noon, day-first per the established STC/D360 convention.
2. `FinancialRecordingAdapter.kt`: admit `stcpay-online-purchase` to the supported
   template set. All other guards unchanged (EXPENSE + POSTED + EXPLICIT_POSTED +
   stated `bankEventDate` + positive amount).

**Edges.** `5/9/5`-style tokens resolve to no valid year → null → receive-time
fallback (no invention). Universal/generic amount templates remain inadmissible.
Date-only regex runs only after every time-bearing candidate fails.

**Acceptance.** RED→GREEN on exact corpus bodies; full 653-identity parser suite
green (zero regressions); app adapter suite green; actual-archive replay on the
emulator shows the 17 rows record automatically when the certified-purchase
rollout is enabled (964→981 confirmed / 502→485 pending), baseline restored,
recording OFF after acceptance.
