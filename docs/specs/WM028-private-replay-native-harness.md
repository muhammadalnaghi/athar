# WM028 — Private-SMS Replay Native Harness (UX root)

## Goal

A bounded, opt-in, debug-only instrumented harness that replays a privately supplied local SMS
archive (NDJSON, parent-supplied) through the **real production Hilt graph** — real
`SmsIngestionPipeline`, real `RawIngestEvent(source=SMS)` persistence, real encrypted
`BackupRepository` baseline export/restore — on an emulator, with aggregate-only evidence.
No mocks, no fake repositories, no private reads, no device operation from this root.

## Scope honesty

- This UX root is **compile-only evidence** (`:app:compilePersonalFullSmsDebugAndroidTestKotlin`).
  No device/AVD, assemble, or install claims are made here; the parent owns all device gates.
- In this root the bound `BackupRepository` is `BackupService` (snapshot v3). The 21-table v4
  transfer is parent-owned in `wealth-028-fast-integration`; because the harness depends on the
  `BackupRepository` **interface**, it automatically uses the v4 path once that graph is merged.
- Restore goes through the real authenticated import (passphrase AES-GCM decrypt) of whatever
  impl the graph binds. No direct DB replacement, no row deletes, no clear/uninstall.
- Idempotency guarantee demonstrated is **subset-scope** (`transactions.sourceRefId` unique
  index), not all-source semantic dedupe. No classifier/category/financial-accuracy claims.

## Inputs (instrumentation args, exact)

| Arg | Required for | Meaning |
|---|---|---|
| `atharQa.optIn` | both | must equal `wealth-028-private-replay` exactly |
| `atharQa.stage` | both | `replay` or `restore` |
| `atharQa.inputFile` | replay | absolute `files/qa_wealth_028/input-HASH.ndjson` path inside the target app-private sandbox, regular file, ≤ 32 MiB |
| `atharQa.inputSha256` | replay | exactly 64 hex chars; must equal the streamed file hash |
| `atharQa.keepForWalkthrough` | replay | `true` → keep replayed data + baseline artifacts for parent UI walkthrough |
| `atharQa.inputFile` | restore | unused: final cleanup deletes only the exact owned app-private sandbox |

NDJSON row contract (parent's converter output): `sourceId`, `receivedAtLocalOriginal`,
`receivedAtUtc` (ISO-8601), `sender`, `senderSourceColumn`, `directionOriginal`
(`Received`/`Sent`), `messageTypeOriginal`, `body`. Required for ingest: `sourceId`,
`receivedAtUtc`, `sender`, `directionOriginal`, `body`. **Only `directionOriginal == "Received"`
rows are ingested**; Sent/other rows are counted and skipped. Parsed with stock `org.json`
line parser; caps: 10 000 total input lines / 32 MiB; strict string fields, invalid rows and duplicate received source IDs refuse preflight.

## Preflight (refuses without any write)

Exact opt-in token → emulator `Build` evidence → target package `com.athar.personal.debug`
→ safe absolute normalized input path → file exists/size/hash. Invocation with **no** `atharQa.*`
args is JUnit-assumption-skipped; partial args fail loudly.

## Execution

`LocalQaEntryPoint` (debug source set only, normal non-exported `@EntryPoint
@InstallIn(SingletonComponent)`) exposes `SmsIngestionPipeline`, `TransactionRepository`,
`SmsAuditRepository`, `BackupRepository` from the real singleton graph, resolved via
`EntryPointAccessors.fromApplication(targetContext, …)`. The queued dispatcher is deliberately
not exposed: it is fire-and-forget, and the harness awaits each row through the same public
suspend `pipeline.process(event)` the dispatcher itself calls — no polling, no timing success.

`replay`: capture encrypted whole-app baseline via real `BackupRepository.export` (fresh random
fixture passphrase, sandbox-internal, ephemeral) → before-counts → ingest each Received row in
order (per-row failure counted, never fatal) → after-counts → bounded idempotency pass
re-processes at most 64 source-bound financially persisted rows and asserts **zero additional transaction rows** → write local
aggregate report → in `finally`, restore the baseline through real `BackupRepository.import`
unless `keepForWalkthrough=true`. Stale artifacts from an earlier walkthrough replay refuse a
new replay (run `restore` first). `restore`: import baseline, report row deltas, then final
cleanup deletes only the owned sandbox. Partial recovery artifact pairs refuse restore and remain intact. Aggregate JSON
is printed to stdout before deletion; report/exception text carries counts and category words
only — never row content, IDs, money, or causes.

## Evidence recorded (aggregate only)

Fresh run UUID, expected hash echo, input bytes, counts (`total/read/received/sent/ignored/
failed/ingestFailed`), new audit `PARSED/FAILED/IGNORED` counts, bounded replay size, orphan parsed-transaction links, new status/uncategorized counts, transaction/audit row deltas
before/ingest/replay-pass/restore, baseline captured/restored/kept flags.

## Acceptance

Source compiles via the fast gate; harness refuses every unsafe precondition before its first
financial write; only Received rows reach the pipeline; baseline restore returns the app to its
pre-replay state through the production path; cleanup leaves no harness artifacts.


## Parent admission and minimum-API isolation

The parent admits this debug entry point and instrumented harness into the complete21-table Backup integration. The first private replay is planned on the already-owned AtharHermesQaApi28 AVD, whose target personal debug package was absent during read-only preflight. This keeps actual-message data separate from the synthetic Goal/Cash/Debt/Portfolio and family-retention fixtures on API35. No physical phone, uninstall, clear, source packaging or private worker is authorized. Failed first/replay ingestion and orphan-link diagnostics must be emitted before refusal; failed runs do not retain the private dataset. A passed replay is not predictive merchant-AI quality or whole-product financial acceptance.
