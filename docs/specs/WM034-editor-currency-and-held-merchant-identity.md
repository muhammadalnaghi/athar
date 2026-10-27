# WM034 — Currency-safe edits and held-processor identity

## Goal and observed evidence

Complete the corrective editor slice exposed during product4 native acceptance. A transaction editor save currently reconstructs money with Money.of(amount), whose default currency is SAR. A category-only edit of USD or another literal currency can therefore relabel money without user consent. Separately, an actual held processor payment produced an Always-categorize prompt even though a pending authorization descriptor is not a verified merchant identity.

## Ownership and outputs

Keep the current editor, state holder and encrypted transaction repository. Preserve the original transaction currency when rebuilding edited money, including the invalid-input fallback. Do not change amount precision, source identity, receipts, account, dates or financial status. Explicit Confirm remains separate. Expose only the existing pure commit callback to same-module tests; do not add another editor or persistence path.

The shared merchant-specificity boundary rejects explicit processor hold descriptors (*PEND, * PEND, *PENDING) in either normalized or display evidence. Reject them before normalizing away contradictory evidence. Apply the same check to direct specificity calls so editor prompting, optional suggestions, rule learning, CSV merchant grouping and recurring detection cannot treat a held processor label as specific identity. Preserve real merchants such as PEND*BOOKS and longer ordinary words; this is not a broad PEND substring ban. No payment-posting policy changes.

## Acceptance and limits

Add focused regression cases that execute the real editor commit callback and merchant-specificity function; establish actual assertion failures against existing behavior, retain prior testcase identities, then pass after the minimal correction. Exercise a foreign-currency transaction edit through the real emulator, save/reopen and verify its literal currency and pending status. A held processor category edit must save without the Always-learn interruption. Preserve existing financial data and use only the owned disposable AVD. Reuse unchanged product4 financial/private-archive evidence where applicable instead of repeating the entire archive for a UI-only change. Bills and portfolio corrections remain isolated parallel lanes until separately admitted and natively exercised.
