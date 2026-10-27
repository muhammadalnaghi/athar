# Saved planning inputs — parallel-wave contract

## Goal and ownership

Make the existing Goal Solver, debt and cash what-if forms durable, and provide the same saved-input boundary for dated securities portfolios. This contract stores user-authored planning inputs, not posted financial events, computed balances, or certified forecasts. Existing encrypted Room remains the sole persistent source. Do not create a plaintext preferences/file store or silently use production in-memory defaults.

The controller owns the frozen domain contract. The store lane owns its Room entity/DAO/repository/Hilt binding, additive migration, exported schema and lossless backup compatibility. Goal/debt/cash/portfolio lanes own versioned input serialization and reachable save/list/load/explicit-delete controls in their existing screens. No feature depends on another feature. Existing scenario calculators and exact-money renderers are reused, not replaced.

## Inputs, outputs and edges

A SavedFinancialPlan contains immutable identity/kind, a user title, literal currency, versioned input JSON, optimistic revision and creation/update timestamps. JSON is an opaque local planning document; each feature validates its supported payload version and input fields when loading. Monetary quantities remain original decimal strings. No cached computed outcome is treated as persisted financial truth. Changing currency must never relabel old inputs, and malformed/future documents must remain retained with an explicit unavailable state.

Repository observe/get expose immutable saved inputs. Save with expectedRevision=null is create-only. Update requires the exact current revision; delete also requires a matching revision and an explicit user action. Conflicts must not silently overwrite a newer edit. Kind and creation time remain immutable; revision overflow refuses without writes. Resource limits and safe error messages are enforced before persistence. Repository calls are main-safe, cancellation-preserving and transactional.

## Acceptance

Verify create/edit/reload/process recreation, conflict/refusal/no-write cases, different-kind isolation, exact three-decimal money, malformed/unsupported JSON, literal currency, safe deletion, migration preserving every incumbent table/row, and export/restore of every saved document. Older backup formats must not silently erase newer planning records. Existing family pools, Plan001, goal calculations and temporary scenario behavior must remain unchanged. Independent review, integrated builds and parent-owned native/encrypted-history checks remain release gates. No automatic recording, backup-v4 activation or publication is authorized by this contract.
