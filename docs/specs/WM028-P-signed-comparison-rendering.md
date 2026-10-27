# WM028-P — Signed comparison-bar rendering parity

## Goal

Make rendered comparison bars reflect the existing exact signed geometry. A positive expense and an equally large negative savings value must occupy equal vertical magnitudes on the same scale, on opposite sides of zero. Keep the new comparison taps and existing exact localized money labels unchanged.

## Observed defect and root cause

The actual owned API35 screenshot after the old non-working expense-bar tap shows no evidence sheet. It also supplies an independent rendering counterexample: at the solid centers of the known equal-magnitude bars, expense spans210pixels and negative savings spans105pixels. The existing geometry is correct; the comparison renderer places a fractional Spacer followed by a fractional bar inside a Column. The second fraction consequently measures the remaining height, not the full plot. The monthly chart already uses the correct full-constraint offset/height implementation.

## Implementation contract

Reuse that monthly-chart implementation in the comparison SignedBar: a constraint-aware full plot, absolute vertical offset from topFraction and absolute height from heightFraction. These are dimensionless drawing projections, not Float/Double money conversions. Zero must stay visually zero while its whole cell remains tappable. Preserve full exact money accessibility labels, both current/prior identities, literal currency and the shared signed range. Preserve the parent's minimum48dp cells; allow horizontal scrolling when six cells plus token spacing exceed the viewport, rather than shrinking targets.

## Acceptance

Retain all84prior affected test identities and the4new drilldown cases. Build both explicitly unseeded flavors and inspect compiled asset/permission boundaries. On the same-identity signed emulator update, the previously failing comparison tap must open evidence; current and prior evidence windows and source records must remain exact. Inspect a new screenshot and measure both solid bar-center pixel spans: equal input magnitudes must have equal rendered heights within pixel rounding, not the prior2:1error. Verify the narrow-layout touch bounds separately. No phone operation, backup-v4 activation, schema/crypto change, publication or full-product acceptance is authorized by this checkpoint.
