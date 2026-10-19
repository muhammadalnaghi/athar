# ADR-002: Local-first architecture, no backend in v1

- **Status:** Accepted
- **Date:** 2026-05-24

## Context

Master Brief §5.3 commits to a local-first architecture: no backend in v1, no sync, encrypted SQLite as the only system of record, user-initiated encrypted export as the only escape hatch. §2.2 codifies this as the second of three "Athar will never" promises.

## Decision

- All persistence is local: Room over SQLite, encrypted at rest with SQLCipher, key derived from Android Keystore.
- Athar has no in-app network client for receipt import, OCR, financial data, model data, or diagnostics. Explicit user-initiated export/share remains an OS handoff to another app and destination the user chooses; Athar does not upload it itself. The only in-app network exception is the signed public merchant catalog described below, and it never authorizes financial uploads, receipt uploads, runtime model downloads, diagnostic telemetry, or network/diagnostic ML Kit SDKs.
- No analytics. No crash reporting to a third party. Crashes write to a local file the user can attach to a bug report manually.
- Backup format: zipped JSON of all tables + raw SMS audit, AES-256-GCM, Argon2id passphrase. Decryption tool published alongside the app so the user is never locked in.

## Consequences

- Multi-device sync becomes a future feature, not a v1 fix. If/when added, it must be opt-in and end-to-end encrypted.
- Customer support cannot fetch logs; users must volunteer them.
- Restore flow becomes a P0 testing target — corrupt or unrecoverable backups violate the privacy promise.
- The `ingestion/` source layer is interchangeable (SMS, notifications, future Open Banking adapter) so distribution flavors do not require core rewrites.

## Alternatives considered and rejected

- Supabase / Firebase backend — fastest path to multi-device, but breaks §2.2.
- Plain (unencrypted) SQLite — acceptable for debug, unacceptable for release. SQLCipher is non-negotiable for v1 release.

## Amendment — 2026-10-16 — opt-in signed shared merchant catalog

Supersedes the blanket "no network calls" reading of this ADR for **one scoped capability**: a personalFullSms-only, opt-in (off by default), whole-catalog download of a signed public merchant→category library from one fixed HTTPS raw-GitHub endpoint on a data-only `merchant-catalog` branch. Bounds: GET only, redirects disabled, fixed generic User-Agent, no query strings or identifier headers, foreground checks after seven days or manual refresh while opted in, bounded retry/backoff, cancellation on opt-out. No periodic job, no financial uploads, no telemetry; the endpoint is public data addressed by a fixed path, not an API keyed to the user. The catalog never enters `category_rule`/Room — signed envelopes live in app-private AtomicFile caches, opt-in/timing metadata in a dedicated nonfinancial DataStore file. StoreSafe remains fully offline (no `INTERNET`, transport-level guard, unavailable toggle). Key/signature trust anchors to a build-time public SPKI (`SHA256withECDSA/P-256`, keyId `p256-3eee06efb538aeb5`); rotation requires a trusted Obtainium app update. Obtainium stays the only app-update channel (see ADR-009).
