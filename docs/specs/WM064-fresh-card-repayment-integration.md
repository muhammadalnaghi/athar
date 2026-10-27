# WM064 — Fresh completed card repayments without a review chore

## Goal

Connect a narrowly validated bank-card repayment to the existing canonical ledger
and confirmed transaction projection. A completed repayment is a transfer, never
new spending or income. Preserve the successful purchase path, user decisions,
legacy history, family investments and the encrypted restore boundary.

## Inputs and dependencies

WM062 supplies template-bound posting evidence for the actual winning Al Rajhi
credit-card payment template. The amount requires its explicit SR/SAR currency
marker; sender/template identity and conservative contradiction precedence remain
mandatory. Missing source dates stay missing. WM063 supplies a separate guarded
`recordCardRepayment` operation while retaining the existing purchase API and its
strict purchase-only guards. Integrate only after inspecting those real symbols
and binding their exact source digests.

## App mapping

Add a dedicated pure repayment-draft mapper; do not broaden the purchase mapper.
Require the known template, TRANSFER type, explicit POSTED evidence, positive exact
money and genuine bank date. Build CARD_REPAYMENT / OUT with one explicit principal
and unresolved routing through the existing factory. Do not invent ownership,
source/destination legs, settlement, fees or account balances.

The rollout session remains default OFF. For enabled fresh ingestion only, dispatch
this specific graph to the new recorder. A validated repayment is not the generic
“possible savings” self-transfer heuristic, even when the message mentions the
user's account snippets. Unresolved transfers retain their existing treatment.
Existing-source redelivery, category changes, user-edited rows and restored history
must never be reapproved or rewritten.

## Acceptance

Run focused mapper and retained purchase tests, then build the integrated unseeded
candidate. Exercise public positive/hold/missing-date/duplicate examples through
real native ingestion and encrypted persistence. Confirm the payment once as a
TRANSFER with its exact source, date and currency; verify it contributes zero to
operating spend/income and cannot double count its underlying purchases.

The parent then compares the already-authorized message corpus with source-bound
oracles: amounts and financial-row population must remain unchanged, typed holds
remain unconfirmed, and any review reduction must match exact newly admitted
repayment sources rather than a guessed headline count. Capture a nonzero encrypted
baseline, perform authenticated cleanup once, and reopen original History and
portfolio values. No physical-phone operation, mass historical confirmation,
publication, new schema, dependency, crypto engine or plaintext cache.
