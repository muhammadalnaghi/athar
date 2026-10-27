# WM043 — distinguish Bills headline scope from list coverage

## Goal and current evidence

Refine the existing Bills summary copy only. On the owned Arabic API35 screen, the headline correctly says the next30 days, while the subtitle reports the size and end date of the wider displayed list. The observed October25 screen therefore showed a November-sized money window together with a December24 list end date. The implementation deliberately keeps these windows different; the unlabelled subtitle made them look like one financial scope.

## Preserved behavior

BillsViewModel already uses a half-open30-day expense window, filters paid entries, partitions literal currencies and preserves a wider60-day occurrence list and its coverage/matching guards. None of those calculations, dates, item statuses, occurrence bounds, rule edits, reminders or payment-evidence behavior changes. This is not a repair that changes amounts or removes records.

## Change

Keep both current format placeholders and values exactly, but explicitly name the subtitle as the displayed-list count and bound. The Arabic label must avoid an incorrect generic plural for zero/one/two. English must communicate the same distinction. Reuse the existing string resource key so screen callbacks, layout and accessibility roles stay unchanged. Do not add another explanation card or expand the always-visible UI.

## Acceptance

Verify resource placeholder parity and compile both debug flavors in the combined presentation candidate. In the native Bills viewport the30-day money heading and the separately labelled list bound must both be readable, including the empty case. Existing Plan tests and prior case identities remain retained. Wider Bills source coverage, recurrence matching and whole capability acceptance stay separate.
