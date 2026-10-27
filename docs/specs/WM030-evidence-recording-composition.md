# WM030: evidence-based recording composition

## Goal and observed boundary

The current ingestion path ties financial confirmation to a trusted category rule. A reliably completed but uncategorized payment therefore becomes a routine approval task. The authorized WM029 brief amendment separates financial evidence from optional categorization. This slice supplies the parser evidence and prospective ingestion seam; it does not silently reapprove historical records, activate an unverified rollout, or claim that parse success proves money movement.

## Ownership and implementation

Keep the existing pure-Kotlin templates and parsed amounts, currencies, merchants, dates and confidence unchanged. Enrich only a successful registry result with typed posting evidence and an optional source-stated event date. Completed-payment language must be positive evidence. Declined, negated, authorization/hold, scheduled, released, administrative and instruction language takes precedence over any embedded completed-payment phrase. A template match or category score alone remains heuristic. Unknown evidence stays explicit ambiguity, not a fabricated completion.

The app-side adapter will build the existing certified domain graph and request the single legacy-query projection only for eligible supported directional events. The core:data owner must independently verify source identity, exact economics, untouched ingestion row and restore generation within the canonical admission transaction. Capture the session before suspended ingestion work. A reused stored transaction identifier is replay, not authorization to mutate an old decision. The new path must retain the stable stored identifier for audit linkage and never use destructive replacement. Preserve existing category behavior while rollout is off; do not sum canonical and legacy copies.

## Acceptance and limits

Use synthetic Arabic/English positive and contradictory cases, including merchant names containing payment words, future debit, negative completion, temporary holds and refunds. Confirm the existing parser cases and financial population are unchanged. A parent-only opt-in diagnostic may emit aggregate template/evidence/date coverage from the authorized archive, with no raw sender/body/account/amount. Native acceptance must subsequently demonstrate uncategorized completed money counted once, genuine ambiguity retained, replay and user decisions preserved, restore fencing and rollback. A passing parser suite alone neither activates recording nor establishes the full benchmark capability.
