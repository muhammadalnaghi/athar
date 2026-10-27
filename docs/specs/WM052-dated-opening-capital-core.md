# WM052 — Dated opening capital in the pure ledger and reviewed XIRR bridge

**Scope:** `core/domain` only. The ledger document gains an OPTIONAL explicit opening-cash date; the WM042 XIRR bridge admits explicitly dated nonzero opening capital into the reviewed `XirrCalc` economics exactly once. Saved-document codec and all UI/native mapping are owned by the parent task.

## Goal

Undated opening cash is correctly withheld from XIRR today because its date is unknown. Users who know when the capital arrived must be able to state it explicitly, once, and get honest annualized economics — without changing a single legacy behavior for documents that carry no date.

## API surface (parent codec will pass exactly this named argument)

- `PortfolioLedger.of(baseCurrency, openingCash, securities, events, openingCashDate: LocalDate? = null)` — fifth parameter defaults to null; all existing four-argument callers compile and behave identically.
- `PortfolioLedger.openingCashDate: LocalDate?` — new public read-only property.

## Rules

1. **Structural:** the explicit date is stored verbatim (no inference, no repair, and NO new `LedgerRejection` subtype — parent `Workbench.safeName` stays exhaustive). Date-vs-first-event ordering and malformed dates are validated by the parent document codec BEFORE constructing the ledger. Zero opening cash with a date is accepted and keeps legacy economics.
2. **As-of guard:** the bridge withholds before any economics run when nonzero dated opening capital does not exist yet at the as-of date → `ReturnUndefinedReason.AS_OF_BEFORE_OPENING_DATE` (the single new enum case — parent owns UI string mapping). Future capital is never exposed.
3. **Bridge economics:** dated nonzero opening cash enters as `XirrInput.openingValuation = DatedValuation(openingCashDate, openingCash)` — the engine folds it under its own reserved valuation id; no flow id or date is invented, no first-trade/created/received/as-of inference. Later contributions coexist (both capital sources reach the engine). An empty external-flow list with dated nonzero opening is NOT `NO_DATED_FLOWS`.
4. **Preserved:** undated nonzero opening → `MISSING_BOUNDARY_VALUATION`; zero opening / absent date → legacy results; money/ids/event order/negative-cash/currency/precision contracts; TWR stays honestly unsupported without per-flow boundary valuations. No engine (XirrCalc/NPV/grid/bisection/TWR) changes; no old test/ID changes.

## Acceptance

Public fixture: 1000 SAR opening on 2026-01-01, BUY 100 @ 10 same day, quote 12 on 2026-04-01 (as-of same). Total 1200, gain 200, XIRR `(1.2^(365/90) − 1) × 100 = 109.47101751%` at the 8dp facade. New `*Opening052*Test.kt` domain tests cover: fixture, dated-opening+contribution engine equivalence, undated withholding, as-of-before-opening withholding, literal USD, reserved-id non-collision, zero-opening legacy, TWR honesty. The prior 485 domain test-case IDs must all remain green and unchanged.
