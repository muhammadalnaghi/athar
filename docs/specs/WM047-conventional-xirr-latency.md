# WM047 — Conventional-flow XIRR fast path (skip the 4096-point scan)

## Goal

Reduce XIRR latency for conventional cash-flow series (the common import/preview case) by
reusing the existing bounded endpoint evaluation and bisection in
`core/domain/src/main/kotlin/com/athar/core/domain/calc/performance/XirrCalc.kt` instead of
always running the 4096-point grid scan. Native observation on the integrated product
(2026-10): a fully dated synthetic SAR-1000 contribution + 100 units @10 on 2026-01-01 with a
quote of 12 on 2026-04-01 took 10.79 s from file selection to settled preview, including capture overhead. This is not
an isolated device CPU measurement. The result itself was correct: XIRR 109.47101751 %, gain 200, totals 1200.

## Root-uniqueness argument (why endpoints suffice)

After exact same-date netting, let the nonzero chronological net signs have at most one
variation. With `t_i` the actual/365 exponents ordered by date and `v = 1/(1+r) > 0` for
`r ∈ (-1, ∞)`, NPV is the exponential polynomial `Σ a_i · v^{t_i}`. By the generalized
Descartes rule for exponential polynomials, its number of real roots in `v > 0` is at most the
number of coefficient sign variations — here ≤ 1. So within the bounded window
`[-0.999999999999, 100]`: NPV at the two bounds with opposite signs proves exactly one root
(existing `bisect` finds it); an exact zero at a bound is that root; equal nonzero signs prove
no root inside the window (honest bounded-search miss, `NO_SIGN_CHANGE`). No root is ever
picked under ambiguity; nothing is relabeled.

## Inputs / Outputs

- In: `XirrInput` (unchanged). Out: `XirrResult` shapes, statuses, DECIMAL128, actual/365,
  bounds, `RATE_SCALE` HALF_EVEN rounding — all unchanged. Validation, known-zero valuations,
  every-rate-is-root, no-date/zero-span, currency and negative-valuation rejections untouched.
- Non-conventional series (>1 chronological sign variation after netting) keep the existing
  4096-point scan and `MULTIPLE_SIGN_CHANGES` / `UNIQUENESS_UNPROVEN` behavior byte-exact.

## Edges

- Endpoint exact roots (computed `signum()==0` at a bound) return the bound as the root —
  identical to the scan's first/last grid evaluations, which are bit-identical evaluations.
- Conventional root at/near a bound that pow rounding pushes off exact zero stays an honest
  bounded-search miss, same as the scan produces today.
- Optional day-exponent precomputation per call only because `BigDecimal(days)/365` in
  DECIMAL128 is deterministic — identical values, one divide instead of one per NPV evaluation.

## Acceptance

1. New tests only in `core/domain/src/test/.../calc/performance/*047Test.kt`; prior tests
   untouched; all current domain cases keep identity and result (suite green before and after).
2. Focused conventional regressions: bounded interior root (the WM047 90-day public fixture,
   oracle 1.0947101751 ratio), no-root (single-sign series), endpoint-degenerate root, and
   same-date netting that turns a raw double sign change into one net variation.
3. Non-conventional series still report multiple/unproven candidates via the untouched scan.
4. Pre/post production-call JVM timing on the same public fixture, raw samples retained in
   `build/qa/wealth-047/`; no wall-clock thresholds in unit tests.
5. `:core:domain:test --offline -Pathar.seed=false --max-workers=1` (2 GB heap, one slot) green.

## Non-goals

No custom NPV/solver rewrite, no approximate `Double` optimization, no Android/native claims —
the JVM timings are evidence, not a device performance assertion. No public diagnostic API.

## Parent completion boundary

The timeboxed producer authored seven correctness cases, three production-call JVM timing cases and a483-case unchanged-production PRE run, but no optimized production code. Parent recovered the actual timing lines from JUnit XML (the short PRE text omitted them), independently verified all473prior identities, and owns the minimal implementation. Keep the observed native loading counterexample separate (WM048). Reuse NPV and bisection unchanged; if both endpoint evaluations are zero, fall back to the original scan rather than assert a unique endpoint. No new public diagnostic or data/model/storage contract is introduced. Parent adds strict lower-endpoint and out-of-window cases. JVM before/after results are not Android latency acceptance; the combined candidate must complete real file preview, explicitApply, process restart and reopen before scoped closure.
