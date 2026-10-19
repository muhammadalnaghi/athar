# ENH-009 — Signed shared merchant catalog (opt-in vertical slice)

**Status:** Approved scope (plan 002, Task 1) · 2026-10-16
**Authorizing amendments:** `Athar_Master_Brief.md` §2.2 amendment (2026-10-16), ADR-002 amendment, ADR-009 amendment.

## Goal

Ship an opt-in (off by default) downloader for a signed, data-only public merchant→category library that improves suggested categories for everyone, without weakening local-first privacy: no transaction, merchant query, identifier, date, or telemetry ever leaves the device. Catalog suggestions never create financial postings. No AI/model claims — the separately-scoped classifier experiment was rejected and is out of scope.

## Wire contract (frozen)

Envelope: strict UTF-8 JSON, exactly `{"keyId","payload","signature"}`; envelope ≤ 2 MiB, payload ≤ 1 MiB. Signed message = ASCII `ATHAR-MERCHANT-CATALOG/v1\n` + exact decoded payload bytes (never canonicalized). `SHA256withECDSA` P-256, DER; trust anchor is the build-time public SPKI, keyId `p256-3eee06efb538aeb5`. Payload: exactly `{"schemaVersion":1,"catalogVersion":N,"issuedAt":ISO-instant,"entries":[{pattern,patternType,categoryId}]}`. Strict parsing (unknown/duplicate JSON fields rejected; android.util.JsonReader shallow depth/key guard before kotlinx). Entry bounds: pattern 3–128 code points, ≥1 letter, no control chars, no URL/email/phone/account-like sequences, no generic bank/processor keys; `patternType` EXACT|SUBSTRING only; category `cat-[a-z][a-z0-9-]{0,63}`; ≤ 10 000 entries; contradictory duplicate identities reject the whole catalog.

## Behavior

Endpoint fixed: `https://raw.githubusercontent.com/wa1939/athar/merchant-catalog/catalog-v1.json`. GET only, redirects disabled, fixed `User-Agent: Athar-Merchant-Catalog/1`, 10 s connect/read timeouts, 25 s total, always disconnect. personalFullSms manifest only gains `INTERNET`; StoreSafe has a transport-level guard and no toggle. Foreground checks after 7 days or manual refresh while opted in; ≥ 1-day automatic backoff after failure; opt-out cancels in-flight work and discards stale responses. Version rules: new > highest accepted; identical version accepted only with identical authenticated payload SHA-256; lower rejected. Reset keeps the highest floor + digest, clears caches, returns to bundled knowledge; personal rules/history untouched. Caches: current + previous signed envelopes in AtomicFile, re-verified on load; metadata (opt-in, highest version/digest, check times) in a dedicated nonfinancial DataStore file. Precedence across layers: personal (`learnedFromUser`) > `auto-local-` > catalog > bundled > UNKNOWN; catalog SUBSTRING enforces both Latin/digit word edges; missing/archived/kind-incompatible categories skip, never create. Transactions gain nullable `categorySource` (additive Room 7→8, backup-optional, unknown→null); pipeline initial status keys on a valid local-rule match (`hadTrustedLocalRule`), never on catalog presence; learner and confidence sweeps exclude `CLASSIFIER`/`CATALOG` provenance; explicit user edits mark `USER`.

## Acceptance

1. Verifier rejects tampered payload, wrong/unknown key, P1363 raw signatures, malformed Base64, out-of-bounds/unsafe patterns, bad namespace, duplicate contradictions, future `issuedAt`, out-of-order versions (JVM Truth tests + parent-run API 28/35 platform crypto evidence).
2. Repository tests prove: default-off, staleness/backoff gating, coalescing, stale-result fencing, offline last-good, reset/re-enable at the floor, re-verify on load.
3. Resolver tests prove the full precedence ladder and boundary semantics; pipeline tests prove catalog ⇒ PENDING and local-rule ⇒ CONFIRMED.
4. Both flavor debug assemblies + full unit test suite green. October18 authorized exception: TodayViewModel and its test may change only for explicit USER category provenance/regressions; Plan001 operating/transfer behavior and the other five protected files remain unchanged. Preserve the original seven-file hash record rather than silently rebasing it.
5. Settings card (Arabic/English): off initially, candid disclosure (whole public download; host sees IP/timing; financial data stays local), version/checked status, refresh, reset, honest unavailable/failed states, cached-library-offline note.

## Control and resilience clarification — 2026-10-18

Only unlearned auto-local EXACT rules qualify for the auto-local layer; learned personal rules retain precedence for every supported pattern type. Catalog normalization alone collapses whitespace. Resolution takes one defensive entries/version snapshot; completed reset makes subsequent lookups see no downloaded snapshot. Persist a separate AtomicFile disable fence before DataStore revocation, fail closed on uncertain storage, and expose a retry if all durable writes fail. A proved durable fence remains effective after recreation despite failed metadata persistence. Retain session failure backoff when timestamp persistence fails. Actual cache I/O failures must not masquerade as missing data or permanently suppress same-process recovery; independently verified previous candidates remain eligible under the unchanged version/digest floor.
