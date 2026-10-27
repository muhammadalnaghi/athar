# WM033 — Categorization is not financial posting

## Goal

Keep optional merchant/category maintenance separate from the financial decision to count a payment. Reliable completed payments are recorded by the source-evidence path. Assigning a category must never certify a hold, resurrect a dismissed observation, or rewrite an existing personal category. This is a small correction to existing flows, not a new financial event engine.

## Observed path and inputs

Today offers single and bulk history-derived category suggestions. Its ViewModel currently changes a pending transaction to CONFIRMED when applying one. The Always-categorize backfill separately updates pending and dismissed matching rows to CONFIRMED in the transaction DAO. Neither action establishes that funds posted. Inputs remain the selected category, a transaction identity or explicit learned merchant pattern, and the existing row's financial status.

## Outputs and boundaries

A suggestion updates only category, category provenance and modification time. Status stays unchanged. Explicit Confirm is the financial decision. Saving edits, single/bulk category assignments on Today or History, and CSV category maintenance preserve the existing financial status; dismissed rows are never revived by editing or classification. Merchant-pattern backfill may fill an unassigned compatible category on confirmed or pending records, without changing status; it must skip dismissed rows and already assigned categories. Literal income/expense category compatibility is checked, and archived or missing categories are not invented.

No parser certificate, amount, currency, date, account, canonical identity, source evidence, receipt, restore epoch or recorded history is rewritten. No schema or dependency changes are required. Matching preserves the existing explicit merchant-pattern semantics.

## Acceptance

The two existing Today suggestion tests keep their historical testcase identities but assert category-only behavior; they must fail against the old implementation and pass after the correction. Add focused real DAO boundary proof for pending/confirmed/dismissed, existing category and incompatible transaction type. Preserve prior test identities and user decisions. Native acceptance uses the integrated app and existing financial safeguards; never manufacture a lower review count by confirming or deleting uncertain observations. Record actual outcomes and retain failed harness attempts separately from product defects.
