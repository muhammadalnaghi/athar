# WM079 — Plan period-label numerals (Arabic-Indic)

## Goal
Close the last Latin-digit leak in Arabic UI copy: the Plan (الخطة) budget section's period caption rendered `2026/10` (and `2026` for year, `dd/mm/yyyy → dd/mm/yyyy` for custom ranges) in Western digits while every other number on the screen is Arabic-Indic. Found by WM078-b visual review of the Plan screen (vision PASS verdict with exactly this one defect).

## Change
`feature/plan/.../PlanScreen.kt` — `periodLabel()` now wraps its three template-built shapes (`${year}/${month}`, `${year}`, `${start} → ${end}`) with `AtharNumerals.localize(..., currentUiLocale())`, the same shared transliteration helper shipped in WM077 for Today's captions. No money paths touched; money stays on `MoneyTextFormat` (LTR-isolated). EN locale renders unchanged (localize is a no-op for non-Arabic).

## Scope guards
- Expected source delta vs product24: exactly `PlanScreen.kt` (freeze25 asserts).
- Fresh test gate: `:feature:plan:testDebugUnitTest` full suite (≥30 cases) re-run with mtime floors.
- Safe signed update product24 → product25 with encrypted pre-update archive; no financial writes; recording stays OFF.
- On-device acceptance: Plan screen shows `٢٠٢٦/١٠`; zero ASCII `[0-9]{4}/` matches in the Plan dump (ASCII-only class, per the `\d`-matches-Arabic pitfall).

## Acceptance
`PRODUCT25_WM079_SCOPED_NATIVE_ACCEPTANCE_PASS` receipt in `build/qa/wealth-068/` after: verify_install25 (build+artifacts+update) → check_plan79 on the owned AVD → recording-off recheck.
