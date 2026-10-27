# WM057 — Declined attempts are audit evidence, not financial review chores

## Goal
Keep a positively evidenced, single refused payment attempt in the raw SMS audit without creating a financial transaction. An extracted proposed amount does not prove a debit occurred. This is a prospective ingestion disposition, not historical repair, automatic confirmation, or a category rule.

## Inputs and decision
The existing production parser still returns its original result and exact amount/currency. The app may classify a successful parse as a non-posting notice only when its typed posting state is DECLINED and the conservative public DeclinedAttemptNotice055 matcher independently recognizes an event-linked refusal. Both gates are required. A merchant named FAILED or DECLINED, conditional advice, an opaque aggregate decline flag, or unknown wording is insufficient.

Separately booked fees, debits, credits, refunds, reversals, or a successful retry must block the notice disposition, including evidence on the same Reason/Status line. Mixed or genuinely ambiguous evidence remains on the existing financial path without automatic posting. This bounded slice does not certify all pending rows as genuine ambiguities.

## Outputs and preservation
Record the raw event once through the existing audit boundary. For a positively recognized refused attempt, mark that audit entry IGNORED with no parsed-transaction link or payload-bearing error. Do not resolve accounts or categories, insert/upsert/delete financial records, invoke the canonical purchase recorder, or change the rollout preference. Preserve all existing historical records and user decisions; re-delivery is not permission to rewrite history. For every nonmatching result, retain current parser, audit-link, persistence, categorization, and held-money behavior.

## Acceptance
Focused app tests reproduce the current financial-row creation before wiring the disposition and cover English/Arabic refusals, repeated notices, rollout/canonical-recorder bypass, merchant-name collisions, conditional text, same-line booked fees, later refunds/completed retries, and contradictory typed evidence. Existing testcase identities remain intact. The parent verifies actual XML, source hashes, both unseeded debug builds, and a bounded native walkthrough of the integrated candidate.

Private-archive analysis stays parent-local. Preserve the existing 915 source amount/notice expectations: eight declined profiles overlap proposed-amount extraction checks, so do not relabel those checks as proof of actual debit or silently rewrite their amounts. Any native non-posting override requires separately reviewed source evidence and explicit reporting. No archive reduction or startup improvement is claimed from source or mocked tests alone.
