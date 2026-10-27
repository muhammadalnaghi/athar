# WM072 — Explicit category/bucket scope in Trends evidence sheets

**Module:** `feature:trends` · **Type:** UI context defect (bugfix) · **Capsule:** `wealth-072-evidence-scope`

## Observed vs expected

Tapping **أخرى / Other** or **غير مصنّفة / Uncategorized** (or any bucket bar) opens a generic
"Expenses" evidence sheet: amount/date/currency/merchant rows and period context are present, but the
selected bucket is never named — an Other drilldown is indistinguishable from a headline drilldown.
Expected: one calm, precise, Arabic-first line naming the exact bucket, before the time context.
The user asked for clarity, not more chore rows.

## Root cause

`EvidenceContext` (TrendsScreen.kt) renders only period/comparison/currency/transfer lines. No branch
reads `evidence.request`'s `key`, so bucket-shaped requests lose their scope.

## Fix

Add one localized scope `ContextLine` at the top of `EvidenceContext`, derived from a new pure helper:

- `TrendsEvidenceContext072.kt` — `evidenceScopeKey(request: TrendsEvidenceRequest): String?`:
  returns the exact `key` for `Bucket`, `MonthInBucket`, `CategoryComparison`; `null` for `Headline`,
  `HeadlineComparison`, `MonthBar` (unbucketed — no line, no generic filler).
- The key comes from the **immutable** `evidence.request`, never the mutable `query.filters.categoryIds`.
- Labeling reuses the existing `bucketLabel(key, fallbackAr, fallbackEn)` composable — no duplicated
  policy: `UNCATEGORIZED_KEY`/`OTHER_KEY` resolve to their localized labels; known ids resolve via
  `evidence.categoryLabels` (ar→en); orphaned/synthetic ids fall back to the localized
  unknown-category label. Raw unknown ids are never rendered.

## Strings

New `trends_evidence_context_scope` — AR `الفئة: %1$s`, EN `Category: %1$s` — in dedicated
`trends_evidence_scope072_strings.xml` (values/ and values-en/). No existing resource covers this.

## Edges

- Category deleted mid-session: request key survives; labels absent → localized unknown label.
- `MonthInBucket` keeps its exact month line; comparisons keep both window lines — byte-identical.
- Merchant/account/source/type/category filters, currency, transfers-excluded, mixed-currency lines,
  back/close behavior, and totals are untouched.

## Tests

`TrendsEvidenceContext072Test.kt` (pure, no mocks): every request kind; exact key preservation
including synthetic keys (`__other__`, `__uncategorized__`, unicode/orphan ids); null for all three
unbucketed shapes; distinct keys remain distinct.

## Non-goals

No request/membership/currency/data-model change, no new state holder, no dependencies, no percent
formatting changes (separate lane), no file-wide reformat.

## Acceptance

`:feature:trends:testDebugUnitTest --tests com.athar.feature.trends.TrendsEvidenceContext072Test`
green via `scripts/android-env.sh` wrapper; pre-existing context lines unchanged.
