# WM029 — Portfolio product completion (file/paste statement import → preview evidence → explicit apply → reopen)

Status: planning lane, branch `wealth/029-planning`. Base `7a125af` (accepted 707-file snapshot).

## Goal

Close the remaining securities-portfolio product journey inside `feature/plan/portfolio` without
touching any sibling contract: a **visible, reachable** statement import that accepts a **local file
(SAF) or pasted CSV/JSON**, shows **exact per-row evidence/errors** in the preview, applies **only on
explicit confirmation**, persists through the reviewed save path, and survives app
relaunch/reopen. Every number stays recomputed by the existing engines; nothing is rebuilt.

## Inputs (existing, reused — not modified)

- `PortfolioStatementImport` (parse CSV/JSON, validate, preview, merge, duplicates, `canApply`).
- `PortfolioWorkbench` + `PortfolioPlanCodec` (replay, rejection tokens, strict codec).
- `InvestmentsViewModel` events (`ImportPortfolioStatement`, `ConfirmPortfolioImport`,
  `CancelPortfolioImport`) with revision fencing and cancel/conflict paths (parent-verified).
- Design-system primitives (`AtharText`, `InvestmentEditorSheet`, `PortfolioTextAction`,
  `FigureRow`), Thmanyah typography, exact BigDecimal money.

## Real gaps identified (this ticket)

1. **No file path.** Only paste existed; `plan_portfolio_import_no_file` was defined but referenced
   nowhere. Added a SAF `OpenDocument` picker (repo idiom from `SettingsScreen`), text read through
   `ContentResolver` with a hard 1 MiB bound; read failure and oversized files surface honest
   in-place states. No new dependency, no manifest change, no network.
2. **Preview showed counts only.** The preview sheet now lists exact evidence: every row error as
   `line N: <localized problem>`, skipped duplicate keys, the statement-level parse problem, and the
   merged-document rejection resolved through the localized `PortfolioRejectMessage` map instead of
   a raw token. Listing is capped (8 + remainder count) for layout sanity; counts remain.
3. **Zero direct tests for the import engine.** Added `PortfolioStatementImportTest` covering the
   header contract, inline security registration, per-row validation, duplicate skipping, JSON row
   typing, and apply-gating.

## Edits (ownership only)

- `feature/plan/.../portfolio/PortfolioImportSheets.kt` — file picker + evidence rendering.
- `feature/plan/res/values{,-en}/finish_portfolio_strings.xml` — new copy (AR first, no hardcoding).
- `feature/plan/src/test/.../portfolio/PortfolioStatementImportTest.kt` — new focused tests.

## Non-goals

No schema/model-contract/Gradle changes; no XIRR/TWR invention beyond the existing dated-input
gates; no quotes/FX/network; no automatic trades; contributions stay funding, never gains.

## Acceptance

- Paste or file → preview with exact line-level evidence → Apply writes a real saved PORTFOLIO
  document → close/reopen shows recomputed state; Cancel writes nothing; a conflicting external
  edit refuses as CONFLICT without overwrite.
- `:feature:plan` focused unit tests pass through the shared two-lease gate; no emulator required
  from this lane (parent owns native acceptance).

## Parent integration and native acceptance

Mode: Operate. Preserve the existing Athar palette, type, three destinations, family pool, saved portfolio and exact money. Scope only statement selection, preview, explicit apply/cancel and recovery. File opening and bounded strict UTF-8 decoding run on IO in the sheet-owned cancellable scope; null providers and malformed bytes are unreadable, not oversize. Controls stack rather than competing for a full-width primary button. File intake accepts a UTF-8 BOM without altering the remaining text. Test the byte-limit boundary, closure, exceptions and cancellation; no unbounded paste workaround is suggested. Parent native acceptance must select a real local synthetic file through Android Documents, preview and cancel with no saved change, apply explicitly, reopen after process relaunch, reject malformed rows without an Apply control, and preserve the existing family and saved portfolio. Worker JVM green alone is not this acceptance.
