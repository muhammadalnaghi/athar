# WM033 product4 — scoped API35 handoff

## Installed authority

- Integration root: `wealth-028-fast-integration`; no source commit, staging, push or release.
- Main APK SHA-256: `80cb0025b6699d64755bb2bb511b98167a0b9203e469352a412a57823d54156e`.
- Owned `AtharHermesQaApi35`, same package/certificate, signed install-r, encrypted preupdate archive, installed hash read back. No clear/uninstall.
- 782 source bindings; both unseeded debug flavors plus current core/app instrumentation APKs built.
- 896 JVM cases passed; all 885 prior identities retained, 11 added. Ten actual production Room parent/category preservation cases passed on API35.
- Closure: `build/qa/wealth-033/parent-product4-native-scoped-acceptance.json`.

## Delivered and exercised

1. **Classification is not posting.** Today suggestions, Today/History edit saves, History bulk category changes, category CSV updates and DAO learned-category backfill preserve financial status. Backfill skips dismissed/existing-category/wrong-kind/inactive cases and treats merchant substrings literally. Explicit financial Confirm remains separate.
2. **Prospective recording control.** Settings reads/writes the actual existing rollout flag through one domain seam and Hilt binding. Writes are off Main and serialized; the control exposes Switch semantics and recoverable failure copy. Native ON, process restart/ON readback, explicit OFF and restart/OFF readback passed. Default and final runtime state remain OFF; no historical rescan or mass-confirmation was invoked.
3. **Saved dated position on Today.** A strict bounded read-only adapter projects existing balance_anchor inputs from the encrypted saved-plan store. Latest selection is per literal currency and never sums overlapping documents. Liability-only net is negative. Existing QA Position032 appears as entered values, not verified live bank wealth, with exact -1000.02 SAR and its stored date. No re-entry or position save was needed.

## Fresh actual-data execution

The authorized archive ran through the current APK's real Hilt ingestion/persistence/query path: 5,458 received messages, 1,520 financial rows, 749 confirmed, 771 pending, zero first-pass/replay ingestion failures. The unchanged independent partial oracle checked 770 source references (767 money values and three notices) with zero failures. All 29 processor-held payments stayed pending, none confirmed.

On the actual replay copy, one held payment was categorized using the real editor and one-time save. Pending-filter search still returned all 29 holds. Reopening showed the saved category selected and the original amount unchanged. No Always-learn rule was chosen.

Authenticated baseline restore completed: 1,522 rows returned to the original two, the replay audit delta was removed, the sandbox/recovery files were removed, and original exports were unchanged. Afterwards, native readback showed the original 100000001.01 SAR monthly total and 100 SAR budget limit, the saved -1000.02 SAR position, and the original imported portfolio's 899 cash / 110 holdings / 1009 total / 101 basis, without reimport or financial rewrite. Screenshots were visually reviewed. Display remained density420/font1.0.

Private evidence stays under ignored `build/private-results/wealth-033-product4-live/` and the matching replay/restore folders. Do not copy it into source-worker lanes or public reports.

## Open priorities — do not declare full22 done

- Existing transaction editor constructs Money without original.currency: foreign-currency editing can relabel money SAR. Source-inspected, not exercised by this SAR-only hold edit. Fix before broader monetary-edit acceptance.
- A held generic processor descriptor still triggers an Always-learn prompt. The native test chose one-time only. Suppress unsafe identity learning and unnecessary prompting in a corrective slice.
- Bills and portfolio-correction finishers stopped with partial files, no accepted handoff; seven/eight bound paths respectively are recorded in `stopped-partial-lanes.json`. Do not wholesale-copy sibling roots or claim these capabilities shipped.
- 771 pending is not yet a genuine-ambiguity-only workflow. Complete canonical event families, commitments, full investment decisions, liquidity/widgets and remaining benchmark journeys remain open.
- UiAutomator null-root captures and an initial legitimate loading frame were retained. Settled screenshots show a populated app; this is not startup-performance or full TalkBack acceptance.
- No API28 rerun, physical-phone acceptance or release/publication.

This is a verified usable slice, not a zero-bugs, production merchant-AI, complete Excel replacement or full22 acceptance claim.
