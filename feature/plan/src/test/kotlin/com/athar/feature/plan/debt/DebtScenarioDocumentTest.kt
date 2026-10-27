package com.athar.feature.plan.debt

import com.athar.feature.plan.scenarios.DebtLoanDraft
import com.athar.feature.plan.scenarios.debtDraftErrors
import com.athar.feature.plan.scenarios.validDebtDraft
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DebtScenarioDocumentTest {
    @Test fun `round trip keeps every typed character and the literal currency`() {
        val draft = validDebtDraft().copy(
            currency = "KWD", firstDueDate = "2027-01-31", horizon = "24", budget = "1250.500",
            loans = listOf(DebtLoanDraft(name = "عقار", principal = "1250.500", annualPercent = "4.75", minimum = "120.250", fee = "1.500")),
        )
        val json = encodeDebtScenarioDocument(draft)
        assertThat(json).contains("\"1250.500\"")
        assertThat(decodeDebtScenarioDocument(json)).isEqualTo(draft)
    }

    @Test fun `multi loan order and JPY integer typing survive the round trip`() {
        val base = validDebtDraft().copy(currency = "JPY")
        val draft = base.copy(loans = listOf(
            DebtLoanDraft(id = 4, name = "A", principal = "3", annualPercent = "0", minimum = "1", fee = "0"),
            DebtLoanDraft(id = 9, name = "B", principal = "7", annualPercent = "1.5", minimum = "2", fee = "0"),
        ))
        val decoded = decodeDebtScenarioDocument(encodeDebtScenarioDocument(draft))!!
        assertThat(decoded.loans.map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(decoded.loans.map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(decoded.loans.first().principal).isEqualTo("3")
        assertThat(decoded.currency).isEqualTo("JPY")
    }

    @Test fun `future quoted and fractional versions are refused`() {
        val json = encodeDebtScenarioDocument(validDebtDraft())
        assertThat(decodeDebtScenarioDocument(json.replace("\"version\":1", "\"version\":2"))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"version\":1", "\"version\":\"1\""))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"version\":1", "\"version\":1.0"))).isNull()
    }

    @Test fun `malformed containers missing unknown and wrongly typed fields are refused`() {
        val json = encodeDebtScenarioDocument(validDebtDraft())
        assertThat(decodeDebtScenarioDocument("{not json")).isNull()
        assertThat(decodeDebtScenarioDocument("[1,2]")).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"horizon\":\"12\",", ""))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("{", "{\"extra\":0,"))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"budget\":\"50\"", "\"budget\":50"))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"supportedTermsAcknowledged\":true", "\"supportedTermsAcknowledged\":\"true\""))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"name\":\"Home\"", "\"nick\":\"Home\""))).isNull()
        assertThat(decodeDebtScenarioDocument(json.replace("\"fee\":\"0\"}]", "\"fee\":\"0\"},{}]"))).isNull()
    }

    @Test fun `decodable but semantically invalid terms stay structurally decodable and flagged`() {
        val draft = validDebtDraft().copy(budget = "-5")
        val decoded = decodeDebtScenarioDocument(encodeDebtScenarioDocument(draft))!!
        assertThat(decoded).isEqualTo(draft)
        assertThat(debtDraftErrors(decoded)).containsExactly("budget")
    }
}
