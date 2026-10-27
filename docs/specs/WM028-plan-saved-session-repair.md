# WM028 — Saved-session repair and truthful regression oracles

## Goal

Complete the reachable saved Goal, Debt, Cash and Portfolio workflows without losing input edits, changing literal currencies or overwriting newer documents. Preserve the 223-case Plan baseline and its named behavioral contracts. A failing assertion is evidence to investigate, not permission to make production behavior match an invalid fixture.

## Diagnosis

The captured baseline has twelve assertion failures. Inspection found invalid test arrangements alongside the genuine regressions. The cash conflict test expected the original amount even though it had already successfully updated that amount; the external writer changes only the revision. The debt save test discarded its own barrier reference because the fake intentionally consumes that property, leaving the write blocked. The debt load test did not delay repository retrieval at all. The Goal load test edited before requesting the load, contrary to its claimed ordering; another Goal assertion tried to create revision 41 through a repository that assigns creation revisions.

The first full repaired run also exposed an amount oracle that omitted the explicit purchase fee: 1000 minus 5 × 20 minus 1 is 899, not 900. Correct these arrangements using retained barrier handles, explicit delayed reads, a separate preexisting-document fixture and exact before/after storage comparisons. Preserve test identities and refusal assertions. Do not weaken optimistic revision checks or suppress failures.

## Production behavior

Save operations persist immutable tap-time inputs and the captured document revision. Later edits, loads or currency changes cannot redirect a callback into another session. Cash reset/currency changes detach saved identity. A separately titled cash alternative has independent identity for comparisons. Portfolio security-only drafts remain saveable without invented dated performance. Statement previews write nothing until explicit apply. Known missing optional trailing CSV fields may be empty; required values must still validate.

## Acceptance

Run the corrected focused suite before applying repairs to distinguish real failures from fixture defects. Review every donor hunk; specifically reject the cash repair that saves only the previously stored payload instead of edited inputs. Run all Plan tests after the coherent production fixes, retaining prior identities and adding exact updated-input checks. Admit only source-bound files into the working integration, build both flavors and exercise save, update, reopen, comparison and cancellation in the native UI. Compilation and test passes do not establish real-Room UI, actual-message or whole-product acceptance.
