# WM028 — saved-input storage admission

## Goal and boundary

Admit the real encrypted Room saved-input repository behind Goals, Debt, Cash and Portfolio without presenting saved scenarios as posted financial events. Preserve the accepted Trends UI and every incumbent schema-10 table. The controller remains the sole integration writer; no publication, phone operation or recording activation belongs to this change.

## Inputs, outputs and failure behavior

The existing domain contract carries an immutable identifier, kind and creation time, literal currency, a positive payload version and opaque JSON input. Create is insert-only. Update and delete require the exact stored revision. A successful update returns this caller's own values, not a later writer's row. Overflow, malformed documents, conflicts and cancellation must not mutate storage.

The production raw-token guard requires an object root, rejects duplicate keys and trailing documents, and enforces the declared depth, member and text budgets before persistence. JSON work runs off the main thread. Unknown positive payload versions remain stored; each feature decides whether it can load them.

Schema 11 adds only the saved-plan table and index. Register migration 10→11 in the actual production builder, retain downgrade refusal, and preserve the authoritative schema-10 export rather than reconstructing history.

## Acceptance and exclusions

The source boundary has 18 repository JVM cases and three real encrypted-Room native methods, executed on both API 28 and 35. Native coverage includes migration/reopen, exact decimal strings, create conflict, revision update and production malformed-input refusals. Generated schema comparison must retain all 20 prior descriptors. Target compilation and application journeys remain separate gates after transfer.

Do not admit the donor's competing legacy-format version-4 backup extension or its five compatibility cases. Complete, bounded, atomic 21-table backup belongs to the single existing backup-v4 lane. Saved-plan product/release acceptance remains blocked until that integration and the actual native save/reopen journeys are verified.
