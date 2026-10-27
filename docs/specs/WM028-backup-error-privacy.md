# WM028 — Safe native backup failure feedback

## Goal

Keep encrypted backup export/import recoverable and understandable without exposing crypto, file-provider, database or payload-bearing exception messages in Settings. Preserve the existing authenticated complete21-table service, stream limits and ownership, passphrase cleanup, cancellation semantics, schema, file pickers and whole-replace transaction. No financial interpretation or data migration changes belong in this repair.

## Observed failure and root cause

On the owned synthetic API35 app, the parent exported an encrypted backup through the real Android document picker, deleted one saved QA goal deliberately, then selected the exported backup with an incorrect synthetic passphrase. The import correctly refused, but Settings rendered `error:...BAD_DECRYPT` instead of its existing Arabic wrong-password/corrupt-file message. The ViewModel copies `Exception.message` into BackupStatus and BackupCard displays that value in preference to localized product copy. Export has the same diagnostic exposure and must be repaired as a sibling boundary.

## Implementation and recovery

Public resolver/URI methods retain their signatures. Add narrow internal stream-opening entry points that execute the same real BackupFileTransfer path, so JVM state tests need no fake Android URI or ContentResolver. Never copy exception messages into backup failure state. The UI independently renders only its fixed localized import/export failure resources, even if a stale failure state contains diagnostic text. Use caution coloring for a recoverable failure rather than destructive-confirmation coloring. Cancellation must still return to Idle and propagate; an ordinary refusal must retain a retry path.

## Acceptance and exclusions

Add synthetic import/export exceptions containing recognizable diagnostic text and verify failure state contains no detail; verify retry can reach success through the same transfer operation. Retain all existing Settings test identities. Run the affected Settings suite, integrate only reviewed hunks, build the signed unseeded debug app and confirm native wrong-passphrase refusal, correct-passphrase restore, and reappearance of the intentionally removed saved goal. Keep the original failed native trace. No private archive, physical phone, app clear, recording activation, release or publication is authorized by this spec.
