# WM071 — Localized Trends percentage display

## Goal

Trends renders every percentage through the app's overridden UI locale instead of the OS default. Arabic screens currently mix Latin digits from `String.format` into Arabic copy and render multi-million-percent deltas ungrouped (`215240017%`). This slice introduces one small pure formatter, wires the six Trends call sites, and keeps money, ratios, calculators and rounding policy untouched.

## Behavior

Inputs are already percentages — `44.3f` means 44.3% — and are divided by 100 only because the JDK percent instance multiplies by 100; callers never rescale. `TrendsPercentage071.number()` returns digits only (resource strings append their own ٪/%); `token()` returns the full localized percent token with grouping and sign, `magnitudeToken()` the unsigned magnitude for arrow badges. Existing rounding policy is preserved: 0 fractional digits for delta badges, up to 1 elsewhere. Non-finite values return null and call sites withhold the meaningless ratio exactly like the existing zero-baseline branch — never NaN, Infinity or a fabricated zero.

## Call sites wired

Headline delta badge, expense-percent-of-income caption, savings-rate caption, savings-rate comparison (both rates required non-null), and the category-comparison delta percentage. No string resources change; the app's Arabic-first copy already carries its own percent wording.

## Tests

Pure JUnit/Truth tests cover English and Arabic shapes: one-decimal number without a percent sign, already-percent inputs never becoming 4,430%, thousand grouping, explicit plus for positive deltas, Arabic digit shapes with the Arabic percent sign and no Latin-digit leakage, and null for NaN/±Infinity in every entry point.

## Limits

Display-only. No money formatting, currency conversion, ratio computation, state holder or data-model change; Trends only. Native Arabic rendering is re-verified on device by the product acceptance slice that follows the build.
