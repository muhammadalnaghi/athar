package com.athar.feature.plan.portfolio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PortfolioAllocationInputTest {
    private fun draft(vararg percentages: Pair<String,String>) = allocationDraftOf(emptyList()).copy(percentages=mapOf(*percentages))
    @Test fun `exact one hundred percent produces exact point fractions`() {
        val valid=validateAllocationDraft(draft("CASH" to "50.00","EQUITY" to "50.00")) as AllocationTargetResult.Valid
        assertThat(valid.targets.map { it.fraction }).containsExactly("0.5000","0.5000")
    }
    @Test fun `invalid total and unsupported precision negative or exponent fail without clear`() {
        assertThat(validateAllocationDraft(draft("CASH" to "90"))).isEqualTo(AllocationTargetResult.Invalid(AllocationWriteProblem.TOTAL))
        for (value in listOf("-1","1e2","0.0000001","101","not a number")) {
            assertThat(validateAllocationDraft(draft("CASH" to value))).isEqualTo(AllocationTargetResult.Invalid(AllocationWriteProblem.INVALID))
        }
    }
    @Test fun `empty or zero targets cannot silently clear the plan`() {
        assertThat(validateAllocationDraft(draft("CASH" to "0"))).isEqualTo(AllocationTargetResult.Invalid(AllocationWriteProblem.EMPTY))
        assertThat(validateAllocationDraft(draft().copy(clearAll=true))).isEqualTo(AllocationTargetResult.Valid(emptyList()))
    }
    @Test fun `unchanged exact fraction literals survive the input roundtrip`() {
        val original=listOf(TargetInput("CASH","POINT",fraction="0.500000"),TargetInput("EQUITY","POINT",fraction="0.500000"))
        assertThat(validateAllocationDraft(allocationDraftOf(original))).isEqualTo(AllocationTargetResult.Valid(original))
    }
    @Test fun `unchanged zero point and original ordering are preserved`() {
        val original = listOf(TargetInput("CASH","POINT",fraction="1.000000"), TargetInput("EQUITY","POINT",fraction="0.000000"))
        assertThat(validateAllocationDraft(allocationDraftOf(original))).isEqualTo(AllocationTargetResult.Valid(original))
    }
    @Test fun `ranges remain untouched while unrelated point targets change`() {
        val range=TargetInput("EQUITY","RANGE",minFraction="0.2500",maxFraction="0.7500")
        val input=allocationDraftOf(listOf(range)).copy(percentages=mapOf("CASH" to "20"))
        val actual=validateAllocationDraft(input) as AllocationTargetResult.Valid
        assertThat(actual.targets.first()).isEqualTo(range)
    }
    @Test fun `Arabic percentage input uses exact decimals`() {
        val actual=validateAllocationDraft(draft("CASH" to "٥٠٫٠٠","EQUITY" to "٥٠٫٠٠")) as AllocationTargetResult.Valid
        assertThat(actual.targets.map { it.fraction }).containsExactly("0.5000","0.5000")
    }
    @Test fun `unsupported and duplicate originals are retained rather than overwritten`() {
        for (original in listOf(listOf(TargetInput("FUTURE","POINT",fraction="1")),
            listOf(TargetInput("CASH","POINT",fraction="0.5"),TargetInput("CASH","POINT",fraction="0.5")))) {
            assertThat(validateAllocationDraft(allocationDraftOf(original))).isEqualTo(AllocationTargetResult.Invalid(AllocationWriteProblem.UNSUPPORTED))
        }
    }
}
