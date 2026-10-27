# WM058 — Existing-portfolio opening-capital date edit

## Goal

Add, correct, or clear `openingCashDate` of an existing saved portfolio without
recreating it. Reuse the version1 document, codec, reviewed engine and revision-CAS
storage. Creation already supports the optional date; existing portfolios need the
same explicit evidence control.

## Scope

One optional-date editor and an inline reviewed preview, reachable from the opened
portfolio. Prefill the stored ISO string or blank, validate the exact `YYYY-MM-DD`
literal, replay off Main, and reuse the existing entry-correction save protocol.

## Protocol

1. **Preview** (`PreviewPortfolioOpeningDate(planId, dateText)`): revision fence
   first (session revision must equal stored revision, else CONFLICT before any
   replay). Blank text means a deliberate clear. Non-blank text must parse through
   the same strict parser used at creation; unparseable, or a value identical to the
   stored one, is refused as INVALID_INPUT with nothing written. The corrected
   document replays through `PortfolioWorkbench.build` off Main; any engine
   rejection (including `MALFORMED_OPENING_DATE` for a date after the first entry or
   as-of) is surfaced as the safe token and nothing is captured. Otherwise the
   before/after exact totals and the captured document + revision are held for one
   explicit save.
2. **Confirm** (`ConfirmPortfolioOpeningDatePreview(token)`): one-shot; a stale or
   superseded preview refuses as CONFLICT before encoding or writing; the CAPTURED
   document is saved under the CAPTURED revision CAS.
3. **Return to editing** (`CancelPortfolioOpeningDatePreview`): writes nothing,
   drops the review and reopens the editor with its exact reviewed draft. Dismissing
   that editor abandons the draft without changing storage.

## Preservation

Only `openingCashDate` changes. Keep event IDs/order, exact amount and currency
literals, allocation metadata, quotes and notes unchanged.

## Honesty rules

No first-trade/today defaults, no invented history. Clearing the date deliberately
returns annual return availability to withheld (`XirrResult.Undefined`), exactly as
the legacy undated contract dictates; the sheet states this consequence before the
user reviews. The opening cash amount is not editable here; the editor is not
broadened.

## Tests

`PortfolioOpeningDate058EditTest` (focused unit tests over the frozen repository
fake): prefill → invalid/impossible/no-op/order refusals write nothing → valid
preview (storage untouched) → explicit save → close/reopen keeps the date and every
unrelated literal → deliberate clear withholds annual return and can be re-dated →
cancel writes nothing → stale revision refuses before write and preserves the newer
stored revision.

## Out of scope

Creation input (WM054), codec/schema changes, engine changes, amount editing,
native/device acceptance (parent exercises the live flow).

## Parent integration acceptance

Preserve the exact submitted ISO date: nonempty padded or whitespace-only values
are invalid, not silently normalized into another value or a clear. Only the empty
string clears. Keep the editor and its draft visible on calendar, ordering, no-op,
storage, or revision refusal; dismiss only when the reviewed preview exists. Reuse
the localized write feedback and automatically reveal the placed preview. Cancel,
close, a newer date request or another portfolio operation invalidates in-flight
date previews. Repeated confirmation must issue exactly one repository save.
Native acceptance uses a disposable saved portfolio, then deletes only that fixture
and reopens the retained original; no private-message reingestion is needed.
