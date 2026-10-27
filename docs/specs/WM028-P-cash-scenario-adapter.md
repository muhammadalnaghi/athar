# WM028-P — Cash Scenario Draft Adapter (pure, feature/plan)

**Goal.** Convert the free-text cash scenario draft (`CashScenarioDraft`) into a validated `CashForecastRequest` and run the real `CashForecastCalculator`, returning `ScenarioDraftResult<CashForecastOutcome>`. The adapter is pure: no clock, no I/O, no UI, no ViewModel, no edits to the calculator or to the parent-owned `ScenarioInputs.kt` helpers.

**Inputs.** `CashScenarioDraft` (frozen): currency, opening date, signed opening cash, optional reserve, horizon end, two completeness attestations, and up to 24 manual flow rows (`id`, `date`, `amount`, `direction`).

**Parsing.** Reuses the parent-owned helpers only: `parseScenarioDecimal` (rejects malformed grouping/exponents/inner whitespace; Arabic-Indic digits normalized; scale ≤ 12), `parseScenarioDate` (strict ISO yyyy-MM-dd), `validScenarioCurrency` (ISO-4217, ≥ 0 fraction digits). Money is never quantized: KWD keeps 3-decimal fils exactly.

**Validation → exact error keys.**
- `currency` — not a valid ISO currency.
- `openingCash`, `openingDate` — required; opening cash may be negative (overdraft).
- `horizonEnd` — parseable, ≥ opening date, ≤ opening + 5 years, and ≤ the calculator's actual limit of 3650 days (read from `CashForecastValidation.MAX_DAYS`).
- `reserve` — blank ⇒ unknown (`null`); unparseable or negative ⇒ `reserve`; explicit `0` is a known zero.
- `cashFlows` — more than 24 rows, or duplicate row ids.
- `flow-${id}-amount` — must parse to a strictly positive magnitude (> 0); direction supplies the sign.
- `flow-${id}-date` — must parse, be strictly AFTER the opening date (opening is end-of-day; a same-day flow would risk double counting) and ≤ horizon end.

Any error ⇒ `errors` only, `outcome` null. Only fully validated drafts carry a calculator outcome.

**Mapping (validated drafts only).** Every manual row becomes `CashEffect(effectId = "flow-<id>", amount = signed Money, valueDate, certainty = EXPECTED)` — all rows stay EXPECTED. Anchor: `CashAnchor(openingDate, Money.of(openingCash, currency), reserveMoney?)`. `knowledgeAsOf = openingDate`; `reservations = emptyList()`; `attestation = CompletenessAttestation(draft.scopeComplete, draft.anchorFresh)` — attestations pass through verbatim, never defaulted to true. The calculator's outcome (`Forecast` or `Rejected`) is returned as-is; the adapter never fabricates a headline and never maps rejection reasons into draft error keys.

**Edges.** Negative opening cash is valid; negative reserve is not. Relative window checks for flow dates run only against successfully parsed references. Deterministic validation order; errors form an insertion-ordered set.

**Acceptance.** 8–12 JVM tests against the real calculator: missing required fields; invalid grouping/exponent; blank reserve stays unknown ⇒ partial; false attestations ⇒ partial; explicit zero reserve + both attestations ⇒ confident zero; KWD `10.123 + 0.500 = 10.623` exact; one-fils shortfall; outflow sign; same-day/before-opening/after-horizon rejections; multi-row evidence ids; negative reserve; row cap. Authored NOT RUN in this worktree (parent-owned `ScenarioInputs.kt` is not present here); the parent integrates against the real helpers and runs.
