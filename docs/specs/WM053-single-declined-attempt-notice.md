# WM053 — A single declined attempt is a notice, not a pending expense

## Goal

A known-bank SMS that positively identifies one declined payment attempt must not
become a pending financial row merely because it states the proposed amount. Banks
without a dedicated decline template (e.g. BSF, which has no earlier bank-specific
parser) currently fall through the global ignore boundary into the universal
amount fallback, which emits a pending expense for money that never moved.

## Contract

Positive identification only, via line-headed fields, never the word "declined"
appearing anywhere in the body:

- `Reason:Declined due to insufficient balance` — a `Reason:` field whose value
  begins with a decline verb (declined/rejected/unsuccessful/failed) or carries the
  insufficient-balance cause.
- `Status:Declined` — a `Status:` field whose value begins with a decline verb.
- Conservative Arabic counterparts of the same line-headed fields:
  `الحالة:مرفوضة` and `السبب:… عدم كفاية الرصيد`.

Numeral script is normalized through the existing normalizer (Arabic-Indic digits
supported). A pure decline supports no movement; the amount on it is proposed, not
spent.

Any separate completed monetary clause in the same body — a debited fee
("A fee of SAR 1.00 was debited"), a later successful retry ("payment completed"),
a refund/reversal, or any line with explicit posted-language evidence — blocks the
ignore and keeps the body parseable by the existing templates. Aggregate
DECLINED evidence must never hide that separate clause, so the posted-language
check is per line, and the header lines themselves are exempt from the
movement-vocabulary guard.

Status/wording that is only Pending or authentication-related never matches. A
merchant merely named DECLINED (no `Reason:`/`Status:` decline header) never
matches. Legacy prose shapes ("Purchase of SAR 25.00 … was declined"), which carry
their own declined posting state, keep their existing coverage unless the exact
new positive family matches.

## Acceptance

Synthetic production-registry tests (sender BSF): the two header families, Arabic
numerals, and the conservative Arabic headers are ignored — no spent/pending row.
Mixed controls (decline + separately debited fee; decline + later completed
payment; decline + refund) remain parseable, as do pending-only, auth-only,
merchant-named-DECLINED, and legacy prose bodies. All existing parser cases and
WM049/050/051 controls stay green. No sender expansion, no history repair, no
generic financial-classification change.
