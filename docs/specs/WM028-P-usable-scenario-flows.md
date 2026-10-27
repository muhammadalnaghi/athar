# WM028-P — Usable temporary debt and cash scenarios

## Goal and scope

Mode: Operate. Finish a reachable vertical slice around the accepted debt and cash calculators and result renderers, preserving Today / Trends / Plan, existing Plan tabs, Thmanyah, exact money and IME-safe scrolling. These are explicit, temporary user-entered what-ifs, not saved debt records, account balances, financial advice or an automatically populated forecast. No database, schema, ingestion, network or canonical activation change.

## Inputs and financial boundaries

Debt takes a visible literal currency, shared first full monthly due date, horizon, total monthly budget, and named loans with principal, annual nominal percentage, minimum payment and fixed monthly fee. Required amounts are never defaulted to zero. The supported nominal-monthly, fixed-fee, penalty-free-prepayment and complete-cycle assumptions require an explicit unchecked-by-default acknowledgement. Missing acknowledgement produces the calculator’s unsupported outcome. The existing debt engine remains the authority for ISO minor units and contractual HALF_EVEN monthly interest; no source amounts are rounded by the form.

Cash takes literal currency, an explicit end-of-day opening date and signed cash, optional reserve (blank means unknown), horizon end and dated planned inflow/outflow magnitudes. Planned movements must be after the opening date to avoid reapplying amounts already in that balance. Scope and opening-freshness attestations are separate, off by default. Partial knowledge stays partial; no synthetic zeros or asserted completeness.

## Implementation and state

Use feature-local pure draft adapters plus one MVI-lite ViewModel, immutable state and typed events. Shared bounded decimal/date parsing supports Arabic digits and validates grouping without silently joining whitespace or accepting exponents. Compute off Main with captured-draft/generation checks; edit/reset invalidates results immediately, cancellation propagates, and a currency edit clears monetary inputs rather than relabeling them. Detail selection is persistent local screen state, with Back returning to the same result and context. Parent Plan owns scrolling and IME insets.

Expose debt from Bills and cash from Goals without adding bottom destinations. Wire real schedule/path callbacks to exact dated calculator output, with bounded pagination that never caps totals or silently drops evidence. Every debt entry shows opening, interest, fee, payment, principal reduction and closing amount; cash points retain cash/reserve/available and supporting identities.

## Acceptance

Add adapter, state-transition, stale-computation, exact KWD/JPY, unsupported-term and partial/known-zero tests. Run actual affected tests and both unseeded debug builds. Gate package/certificate/assets, then rehearse a same-identity update and walk the real forms, keyboard-open compute, details and Back on the owned synthetic AVD without clearing history. Preserve failed attempts. Full WM10/16, actual-data/phone, persisted planning, backup-v4 and release acceptance remain separate open gates.
