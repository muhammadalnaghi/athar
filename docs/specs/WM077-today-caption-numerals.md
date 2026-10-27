# WM077 — Today caption numerals (Arabic-Indic on device)

**Goal.** Vision review of the real-data Today screen (WM070 evidence, re-reviewed
2026-10-26 after the vision endpoint recovered) confirmed the money pills and hero
are bidi-safe and locale-correct, but found three numeral leaks in *caption* paths
built by plain string templates instead of `MoneyTextFormat`:

1. Month label «صافي الشهر · %1$s» received `"${year}/${month}"` → Latin digits
   (`2026/10`) inside Arabic copy.
2. The same raw label feeds the Hijri caption variant via
   `HijriDate.formatYearMonth` (`1448/04`, Latin).
3. Savings-rate caption «معدّل الادخار · %1$s٪» received `"%.0f".format(rate)`
   → Latin digits beside an Arabic percent sign (inconsistent with the WM071
   Trends fix, which transliterates).

(The mirrored-minus / mixed-card findings from the same review applied to
product20-era copy — «آخر وضوح مالي…» strings no longer exist; the current pill
path `TodayMoneyText → MoneyTextFormat` already wraps sign+digits in an LTR
isolate and renders Arabic-Indic via ICU.)

**Root cause.** Android ICU ignores `zeroDigit`/`-u-nu-arab` on device (WM071
lesson): any numeral not deliberately transliterated renders Latin in Arabic copy.

**Change.** New `core/design-system/display/AtharNumerals` — deterministic
transliteration (0-9→٠-٩, `.`→٫, `,`→٬) applied only when the UI locale is
Arabic, digit shapes only, never reordering. The three Today caption call sites
now pass through `AtharNumerals.localize`. Money paths untouched.
`TrendsPercentage071` keeps its private copy (frozen product21 code; dedupe is a
future refactor, not worth re-admitting a hash-bound file for).

**Tests.** `AtharNumeralsTest` (design-system): month/hijri labels, decimal and
grouping separators, EN passthrough, negative sign integrity. Today suite
re-run in the gated build; on-device caption check asserts Arabic-Indic digits
in the rendered caption on the QA emulator.

**Acceptance.** Gated product24 build green (design-system + today + app
suites), safe signed update chain product23→24, on-device Today shows
«٢٠٢٦/١٠»-shaped caption digits, recording OFF, no financial writes.
