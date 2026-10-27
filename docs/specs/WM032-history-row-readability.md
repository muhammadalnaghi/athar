# WM032: Readable History rows

## Surface and outcome

Mode: Operate. Refine existing Today → History only; preserve Athar's Thmanyah typography, parchment/ink palette, single ember accent, existing financial records and filtering/edit behavior. This is not a visual redesign or another application-wide audit.

The current accepted native screenshot shows transaction-type punctuation between separator dots rather than a readable type, a crowded boundary between metadata and trailing money, and small History filter/action chips. The source additionally permits a blank merchant to render an untitled row. The expected result is a named, understandable, reliably touchable record without asking the user to fill optional merchant/category data.

## Change

Use localized singular Income/Expense/Transfer labels rather than isolated plus/minus/angle symbols; reuse that neutral type as the title only when the merchant is blank. Compose metadata from nonblank fields so absent optional text cannot leave empty separator slots. Do not infer a merchant, change a status, invent a category or alter money/sign/currency/date/source. Reserve one existing spacing token between the shared list row's text and money. Give the existing History filter, bulk-action and Back controls the established minimum touch target, without adding controls or changing their actions.

## Acceptance

Compile with the next integrated product candidate, retain the current Today cases, and inspect actual native History rows and controls in the same bounded live walkthrough as the incoming budget/position slices. Confirm exact trailing money remains complete, type/status/source readable, filters still change the visible population and Back still returns to its original route. No new framework or broad test matrix. Existing accepted source-payment and private restore receipts remain historical evidence for their unchanged financial boundaries, not proof that the new APK was already exercised.
