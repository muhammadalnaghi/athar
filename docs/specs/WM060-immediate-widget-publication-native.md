# WM060 — Immediate-exit widget restore acceptance

## Goal

Verify that the placed Monthly and Today widgets retain the authenticated restored
snapshot when the app process exits immediately after restore returns. A queued
Glance update, a foreground-only pass or an already-correct old frame is not proof.
This scope excludes the callback-bearing Pending widget and full WM22 acceptance.

## Inputs and ownership

Use only the owned AtharHermesQaApi35 emulator and the exact same-signer, unseeded
main/test artifacts from a frozen build receipt. Verify real provider components
and widget IDs from AppWidgetManager metadata, not picker labels. The parent alone
owns devices, baseline capture and authenticated restoration. Source workers see
public code and synthetic fixtures only.

The existing production-Hilt replay harness captures the nonzero encrypted baseline
and retains its recovery inputs privately. Reuse its two regression-proven synthetic
messages: one posted SAR3.14 purchase and one held USD5.25 attempt. No full private
archive replay, mass confirmation, plaintext financial cache or password readback.

## Acceptance sequence

1. Verify source/artifact/device identities, recording OFF, display settings and no
   preexisting recovery sandbox. Capture the original host views.
2. Run the existing bounded synthetic ingestion once, retaining the authenticated
   baseline. Require two source checks, one confirmed SAR row and one pending USD
   hold. Observe BOTH actual host views showing their distinct temporary values;
   conditional reads may wait, but must never repeat ingestion to repair a selector.
3. Invoke the existing authenticated restore without the foreground publication
   observer. Once instrumentation returns, force-stop only the exact target package
   before reading reports or waiting for a frame. Do not refresh, rebind or make a
   further financial write.
4. Read actual host views after process exit. Both must show the original exact
   currency-partitioned values. Reconcile the fresh restore receipt, transaction
   count, audit delta and recovery-sandbox cleanup. Reopen original history and
   retained portfolio read-only.

## Failure and recovery

Preserve first failed captures, process timings and precise artifact identities.
Financial replacement success is distinct from widget publication success. A failed
host assertion never authorizes re-ingestion or repeated restore: reconcile the
persisted receipt and current state first. If interrupted before restore, retain the
private baseline and resume that exact recovery stage. If restore already finished,
resume read-only. Keep unavailable/partial publication explicit and preserve the
last-good frame; never describe scheduling as strong publication.
The separate recovery-only controller stage may restore after a failed temporary
frame check, but only before any restore attempt. It never counts as widget
acceptance; interrupted restores require persisted-effect reconciliation instead.
