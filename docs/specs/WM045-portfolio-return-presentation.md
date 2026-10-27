# WM045 — trustworthy return presentation

## Goal and preserved boundary

Present each portfolio-return metric with its own label, honest unavailable state and concise localized percentage. The source-reviewed WM042 facade supplies typed results; the UI must not compute returns, invent dates/valuations, or convert unavailable data to zero. Preserve the9a summary-first hierarchy, full monetary precision, saved input identities, correction-preview tokens, allocation controls and all financial callbacks.

## Presentation contract

Available values are already percentages. Use BigDecimal and upstream locale formatting, at most two displayed decimals, grouping and locale percent symbols. Never multiply a supplied percent by100 or convert money/rates to Float/Double. Preserve the original negative sign even when a tiny negative rate rounds to displayed zero. The underlying8-decimal engine result remains unchanged.

Both Available and Undefined branches name XIRR or TWR. Structural missing evidence with no security IDs gets a complete fixed sentence, never a dangling named-security suffix. Actual missing-security IDs stay visible. Missing start dates, ambiguous/non-unique rates and missing observed flow valuations are unavailable—not financial zeros or chores the user must clear. Optional TWR guidance explains the actual required before/after observations; XIRR never assigns an undated opening balance the first trade date. Monetary gain may include unrealised valuations, so label it gain or loss rather than cash received.

## Acceptance and scope

Retain all352 prior Plan cases and all eleven producer test identities; add a small signed-rounding edge regression. Compile both unseeded debug flavors with the reviewed core facade. Natively verify the original revision2 value899/110/1009 remains intact while unsupported percentages disappear, and a fully dated contribution/trade/quote fixture displays109.47percent without rescaling. Review actual Arabic rendering. This does not certify fullTWR input support, every locale/size/accessibility state, phone or release.

The producer omitted its requested spec file; this parent specification is written before source admission. Producer resource-count prose is not authoritative; actual XML membership is verified independently.
