# WM028 — Atomic source-bound ingestion replay

## Goal

Re-deliver an already observed source without another financial row, uniqueness exception, replacement of the stable transaction ID, loss of receipts, or changes to user-owned financial facts and decisions. Preserve the stricter generic update-then-ABORT upsert. Never restore SQLite REPLACE to hide the defect. This repair does not activate canonical recording or auto-confirm previously pending money.

## Native evidence and root cause

The parent exercised an authorized local archive through the actual debug Hilt graph and encrypted app database on the owned API28 AVD. Initial ingestion persisted financial rows without a thrown pipeline error. Every selected replayed source then failed its unique source-reference insertion. The parser constructs a fresh UUID and the caller invokes generic upsert, whose correct strict collision behavior refuses that new ID. Returning the fresh ID after a source collapse would also orphan audit links. No private records belong in this spec or regression corpus.

## Contract

Add an explicit repository ingest operation, with no unsafe default. A DAO Room transaction performs source-reference lookup and insert atomically. An existing literal reference returns its entire unchanged stored row, including original identity, account, exact money/currency, dates, classification, status, notes, timestamps and receipt links. No reparse silently rewrites a user correction: repairing historical facts is a separate preview/reversible operation. A genuinely new source inserts once; concurrent arrivals get the same stored ID. Null references remain independent observations and do not acquire a semantic-dedupe promise. Generic upsert still refuses another row’s source-reference collision.

The repository returns the actual stored ID, avoids repeating creation logs or teaching local classification from ingestion, and refreshes widgets only for an inserted financial row. Pipeline audit entries must link this returned identity. Keep the original native RED and immutable APK/input/source receipts.

## Acceptance and exclusions

Update explicit test doubles for the added repository/DAO operation without copying stale whole test files. Exercise a native unchanged-row/receipt replay and a concurrent same-source create, retain the strict generic collision test, compile affected callers and execute relevant unit suites. Re-run the real private archive plus bounded concurrent replay through the encrypted app, requiring zero ingest exceptions, unchanged transaction count and no parsed-audit orphan links. Original exports stay byte-identical. Full semantic duplicate-source matching, automatic posting, pending-tray fatigue, parser financial accuracy, phone acceptance and release remain separate.
