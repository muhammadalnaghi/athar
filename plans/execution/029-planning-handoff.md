# 029 planning handoff — portfolio product completion

Lane: `planning` · branch `wealth/029-planning` · base `7a125af` (parent-accepted snapshot, 707 files).
Checkpoint saved at orchestration request (~7min). Updated at completion.

## Implemented scope (exact)

1. **File import path (was missing entirely).**
   `feature/plan/src/main/kotlin/com/athar/feature/plan/portfolio/PortfolioImportSheets.kt`:
   - `PortfolioImportInputSheet` now offers paste **and** a SAF `OpenDocument` picker
     (`text/*`, `application/json`), same in-repo idiom as `SettingsScreen`; no new dependency,
     no manifest/Gradle change.
   - Local read via `ContentResolver`, hard bound 1 MiB (`MAX_STATEMENT_BYTES`); oversized →
     honest `plan_portfolio_import_file_too_large`; unreadable → existing
     `plan_portfolio_import_no_file`. Read success feeds the existing
     `ImportPortfolioStatement(planId, text)` event — ViewModel untouched.
2. **Preview shows exact evidence (was counts only).**
   `PortfolioImportPreviewSheet` now renders: per-row errors as `line N: <localized problem>`
   (capped 8 + remainder count), skipped duplicate keys listed, statement-level parse problems
   (`EMPTY_INPUT`/`BAD_HEADER`/`BAD_FIELD_TYPE`) localized, and merged-document engine rejections
   resolved through the localized `PortfolioRejectMessage` map instead of a raw token.
3. **Copy:** 21 new strings in `feature/plan/src/main/res/values/finish_portfolio_strings.xml`
   (AR) + `values-en/finish_portfolio_strings.xml` (EN). No hardcoded UI copy.
4. **Spec:** `docs/specs/WM029-portfolio-product-completion.md`.

## Verification (complete)

1. New focused engine tests `PortfolioStatementImportTest.kt` — **7/7 PASS** (XML-verified).
2. Gate `wealth029-finish-portfolio` (lane `planning`, slot 0): `:feature:plan:testDebugUnitTest`
   with the three portfolio classes → **19 tests, 0 failures, 0 errors, 0 skipped**
   (includes the parent-verified `InvestmentsPortfolioSavedTest` 5/5 and
   `PortfolioPlanCodecTest` 7/7 — no regressions). Log:
   `build/qa/wealth-029/fast-wealth029-finish-portfolio.log`.
3. Final `build/qa/wealth-029/planning-handoff.json` written with changed-path SHA-256s,
   executed XML test IDs/counts, scope authority and the recipe below.

## Preserved (verified by parent, not touched)

Family-pool/Plan001 ownership; savedGoal/Cash/Debt session fixes; synthetic portfolio
save/relaunch/reopen/recompute (899 cash / 110 holdings / 1009 total / 101 fee-inclusive basis);
localized FigureRow / wrapping action groups / action-specific instrument editor; schema-11 strict
saved-plans codecs; all engines (`PortfolioStatementImport`, `PortfolioWorkbench`,
`PortfolioPlanCodec`, TWR/XIRR/allocation) reused, not rebuilt.

## Parent emulator acceptance recipe (native)

1. Plan tab → Investments → Securities portfolios → **إنشاء محفظة** (name, `SAR`, opening cash) → opens workbench.
2. **استيراد كشف CSV/JSON** → paste or **اختر ملف الكشف** with a 12-column CSV
   (`type,eventId,date,securityId,securityName,assetClass,quantity,unitPrice,fee,amount,destination,note`).
3. Preview shows counts, new securities, duplicates (listed), errors as `سطر N: سبب`; Apply only
   when clean → explicit **تطبيق الاستيراد** persists; **إلغاء** writes nothing.
4. Close → reopen (or kill/relaunch) → holdings/cash/valuation recomputed from stored inputs.
5. Optional conflict: edit the same portfolio from a second session → first apply refuses with
   `plan_portfolio_problem_conflict`, no overwrite.
