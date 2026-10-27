# WM028-P — Debt and forecast currency regression closure

## Goal and scope

Close the stalled, narrow currency-precision verification lanes using real accepted calculators, not another speculative rewrite. The integration checkout is authoritative. Both stopped GLM retries left no test/spec/handoff; no execution or production change is attributed to them. This packet adds tests only in the existing debt and cashforecast test namespaces. It does not add a module, dependency, UI, persistence, provider, financial producer or network operation.

## Existing contracts

Debt already validates principal, budget, minimum and fees against the literal currency's ISO fraction digits. It quantizes only contractual monthly interest, once, using the caller's rounding mode. Do not transplant the Goal solver's meaningful-source-precision policy: unsupported sub-minor-unit debt inputs must remain invalid rather than silently rounded. Cosmetic trailing zeros are not unsupported precision. The cash-path calculator already performs exact BigDecimal addition and subtraction without money quantization, partitions currencies, and withholds unsupported headlines. Do not infer FX, cash anchors, completeness or freshness.

## New evidence

Add four debt cases: KWD fees/final-payment surplus across two debts; half-fils interest ties under HALF_EVEN versus HALF_DOWN; whole-yen half-unit ties under HALF_EVEN versus HALF_UP; and isolated sub-fils principal/minimum/fee/budget rejection alongside cosmetic-zero acceptance. Check both strategies, cash conservation and explicit dates.

Add four forecast cases: posted KWD inflow retaining three digits and initial reserves; a genuine one-fils deficit retaining its exact amount/date; same-day KWD release/increase netting without a fabricated deficit; and independent KWD/JPY balances with no cross-currency merging. Use complete synthetic scope, dated anchors no later than knowledge-as-of, and actual POSTED/EXPECTED enum values.

## Acceptance and limits

Compute independent Decimal oracles with tools before writing assertions. Freeze all prior451 domain case identities and relevant source hashes before execution; run the real full domain suite and verify exact old identities plus the eight new cases, zero failures/errors/skips, and unchanged production sources. A first green run is new regression coverage, not a behavioral RED or a repaired defect. If execution exposes a real defect, retain its evidence and amend the relevant contract before any production fix. These pure-core checks do not close WM-06/10/16, actual-loan adapters, native debt/forecast workflows or full-product acceptance.
