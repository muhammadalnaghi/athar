# WM029: amountless service notices must not become review work

## Goal and observed defect

An explicit parent-only audit of the same 5,458 received messages used in the Android replay reproduces 1,523 parsed transactions, 3,911 ignored notices and 24 failed parses. Inspection classifies those 24 as beneficiary administration, wallet verification, login/PIN/password/service/account-policy notices, or amountless international-transfer status updates. They contain no numeric financial amount. They are not evidence of an additional payment and must not create a financial row or ask the user to categorize an administrative notice.

## Contract and ownership

Extend the existing first-in-registry GlobalBankIgnoreTemplate through one small pure helper for positively identified service/status language. New rules are conservative: apply only when the entire body has no decimal digits, and only to an explicit administration/status phrase. Preserve existing sender allowlisting, templates, raw audit retention, posting/currency/date rules and every financial parse. Unknown amountless bank text still fails rather than being indiscriminately discarded. A completed-transfer status without principal/currency is not a second economic event; the original charged transfer remains authoritative.

## Implementation and edges

Use public synthetic fixtures with substitute names, not original messages, IDs, account details or amounts. Test English and Arabic notice variants and Unicode numeric counterexamples. Bodies combining a service phrase with a numeric amount must continue to the financial templates. Do not expand a blanket ignored-keyword list, grant a category financial authority, modify existing stored history or suppress actual transaction errors to improve a metric.

## Acceptance

Establish an actual synthetic regression RED, then focused GREEN with the production registry and negative controls. Rerun the parent-only archive diagnostic into a separate immutable result directory: parsed count must remain exactly 1,523, all 24 identified source references must move from FAILED to IGNORED, and no additional success may appear. Subsequently exercise the integrated app replay on the owned emulator with authenticated baseline capture/restore. Unit/host diagnostic success alone is not the native acceptance or a zero-bugs claim.
