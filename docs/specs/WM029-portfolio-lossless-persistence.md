# WM029: lossless statement persistence and native recovery

## Observed and expected

The current source-bound Android candidate accepts a real local UTF-8 CSV through Documents, previews one trade and one quote, and correctly discards a cancelled preview. Explicit Apply then retains the portfolio at revision 2 but lists it as unreadable. The synthetic statement includes a note on its BUY. The input encoder emits that note, while the same version-1 decoder forbids notes on trades, dividends and reinvestment. Conversely it wrongly requires a note on funding/withdrawal/fee events although the form and import contract call notes optional. Successful preview and engine replay therefore do not imply successful persisted reload.

## Repair and preservation

Treat note as a common optional string annotation across supported entry kinds. Retain exact monetary strings, literal currencies, existing identifiers, notes, quotes and version 1. Unknown fields and non-string supplied values remain refused rather than silently disappearing. The already-saved synthetic revision must become readable by correcting the reader, not by deleting, rewriting, reimporting or mass-repairing history.

Require import preview and the shared mutation boundary to verify that the encoded document decodes to the identical input before enabling Apply or calling storage. Keep existing engine validation, expected revision and conflict protection. Unsupported field combinations must be explained with fixed localized copy before any write. No schema, migration, calculations, family ownership or automatic-recording activation changes are authorized by this fix.

## Acceptance

Retain the genuine native failure. Add actual failing regressions for all affected note shapes, missing optional annotations, imported encode/decode and the full ViewModel save/reopen path. Preserve strict optional-field typing and unknown-key rejection. Run the affected Plan/app gates, update both owned emulators nondestructively, and reopen the existing retained revision-2 portfolio to recover its exact cash/holdings/total without a new financial write. Then exercise duplicate preview/cancel, malformed-row refusal, process restart and readable Arabic controls. Full WM-11, all-22, phone acceptance and zero-bugs claims remain separate.
