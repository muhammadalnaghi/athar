package com.athar.feature.plan.scenarios

import com.athar.feature.plan.debt.encodeDebtScenarioDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebtForm041PresentationTest {
    @Test fun newDraftAlwaysShowsInputs() { assertTrue(debtForm041ShowInputs(false, false)) }
    @Test fun completedResultIsFirstWithoutRequestingInputs() { assertFalse(debtForm041ShowInputs(true, false)) }
    @Test fun explicitEditReopensExistingInputs() { assertTrue(debtForm041ShowInputs(true, true)) }
    @Test fun hiddenHorizonErrorForcesDisclosure() { assertTrue(debtForm041ShowAdvanced(false, setOf("horizon"))) }
    @Test fun ordinaryVisibleFeeErrorDoesNotHideItsFieldOrForceUnrelatedSettings() {
        assertFalse(debtForm041ShowAdvanced(false, setOf("loan-1-fee")))
    }
    @Test fun openedDisclosureStaysOpenAfterErrorClears() { assertTrue(debtForm041ShowAdvanced(true, emptySet())) }
    @Test fun disclosureNeverChangesLoadedExactTermsOrFeePrecision() {
        val draft = DebtScenarioDraft(currency = "KWD", firstDueDate = "2026-12-25", horizon = "240",
            budget = "25.001", supportedTermsAcknowledged = true,
            loans = listOf(DebtLoanDraft(name = "Loan", principal = "100.001", annualPercent = "0", minimum = "10.001", fee = "0.001")))
        val before = encodeDebtScenarioDocument(draft)
        debtForm041ShowAdvanced(false, emptySet())
        debtForm041ShowAdvanced(true, setOf("horizon"))
        assertEquals(before, encodeDebtScenarioDocument(draft))
        assertEquals("0.001", draft.loans.single().fee)
        assertEquals("240", draft.horizon)
    }
    @Test fun omittedFeeIsStillExplicitlyInvalidNotSilentlyZero() {
        val draft = DebtScenarioDraft(firstDueDate = "2026-12-25", budget = "25",
            loans = listOf(DebtLoanDraft(name = "Loan", principal = "100", annualPercent = "0", minimum = "10")))
        assertTrue("loan-1-fee" in debtDraftErrors(draft))
        assertEquals("", draft.loans.single().fee)
    }
}
