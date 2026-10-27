# WM028-P — Literal recurring-rule suppression identity

## Goal and cause

An existing recurring rule must suppress a detected proposal only within the same merchant, exact minor-unit amount, literal currency, transaction type and cadence. The current detector partitions transactions by merchant/currency/type, but its suppression key omits currency and type. A USD rule can therefore hide a valid SAR proposal, and an expense rule can hide an income proposal. This does not authorize automatic rule creation or change transaction posting.

## Ownership and transfer

The parent reviews the completed wealth-028-commitments implementation, verifies every full SHA-256 against its handoff and the integration preimages, and retains all later DAO safety stubs and exact BigDecimal median regressions. No changes to schema, scheduling, matching, DAO, UI, source identity, Plan001 or network behavior. Reuse the worker’s four narrowly scoped tests after verifying that all 25 existing test bodies are unchanged. Test helper additions preserve existing default behavior.

Introduce a private typed suppression key with merchant, amount, currency, type and cadence. Both existing-rule and suggestion construction must supply the same fields. Currency comparison is literal; merchant normalization remains unchanged. Unsupported enum strings do not acquire a guessed type or cadence and are omitted from the suppression set. The indexed exact median implementation remains byte-identical.

## Acceptance

First apply only the tests against unchanged production code and execute the actual focused test class. Preserve the resulting assertion RED separately from any compilation or fixture failure. Cover foreign-currency non-suppression, different-type non-suppression, same-identity continued suppression and separator-bearing merchant identity. Then apply the reviewed implementation and run the full affected data/Plan suites plus both explicitly unseeded debug builds, retaining every prior test identity. Verify compiled assets, permissions, signatures, exact source bindings and unchanged installed QA APK.

A fresh JVM/build pass does not establish native Bills truth, complete recurring detection, current-phone financial accuracy, automatic recording, or full WM02–04/06 acceptance. No installation, publication, private-data access or canonical activation is part of this slice.
