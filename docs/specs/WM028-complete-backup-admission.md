# WM028 — Complete encrypted backup admission

## Goal

Replace the legacy-only backup service with the single complete v4 envelope before enabling any canonical producer or treating saved planning inputs as durable user data. This is an integration boundary, not completion of the wealth-manager capability matrix. Existing signed-app identity and encrypted history must remain intact.

## Inputs and outputs

The input is the reviewed backup lane’s production service, bounded authenticated intake, canonical transport validation, four auxiliary-table mappings and mandatory stream repository contract. The output is one consistent encrypted archive containing all 21 Room entity tables: legacy transactions and their linked records, canonical events and evidence, activity history, user templates, recurring rules and saved financial documents. Unknown versioned planning documents remain opaque and byte-preserved; restore does not certify their financial authority.

## Behavior and edges

Capture runs in one Room transaction. Restore validates incoming structure before a single replacement transaction containing live eligibility reads, child-first clearing, parent-first insertion and epoch advancement. A legacy archive must refuse a live target whose newer tables would be discarded. Authenticated malformed, oversized, incomplete or unsupported input must not mutate the target. Cancellation closes URI streams, clears temporary passphrase arrays and leaves Settings recoverable. File opening, bounded reading, crypto and writing remain off the main thread. Saved-plan CAS behavior, authoritative schemas 10 and 11, current locale fixes and unrelated Settings changes must not be replaced by older donor versions.

## Acceptance

Bind every admitted file to source and target hashes. Preserve the original 315-data/26-app test identities and run the affected data, Settings and app gates with both unseeded debug builds. Execute all current backup native cases on API 28 and 35. In particular, the corrected SQLCipher round trip must populate all 21 tables and compare every field and blob, permitting only the explicitly asserted singleton advancement. Prior subset-only evidence does not satisfy this gate. Finally exercise the integrated Settings file-picker export, cancellation and authenticated restore on the owned synthetic AVD without clearing or uninstalling the app. No phone operation, publication, producer activation or whole-product completion is authorized by this admission.
