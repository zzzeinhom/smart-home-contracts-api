package com.zzzeinhom.contracts.domain.validation;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClarificationQuestion;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.domain.model.Installment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialGuardrailValidatorTest {

    private static final String SEMANTIC_WARNING = "14% VAT is excluded from quoted unit prices.";

    @Test
    void validateAndFlag_isIdempotentAndPreservesSemanticWarnings() {
        FinancialCommitments mismatchedFinancials = new FinancialCommitments(
                new BigDecimal("10000.00"),
                null,
                false,
                null,
                new BigDecimal("10000.00"),
                "EGP",
                true,
                List.of(
                        new Installment("Deposit", new BigDecimal("4000.00"), "On signing"),
                        new Installment("Final", new BigDecimal("5000.00"), "On delivery")
                )
        );

        List<BillOfMaterialsItem> mismatchedBom = List.of(
                new BillOfMaterialsItem(
                        DeviceCategory.SMART_LIGHTING,
                        "Sonoff Mini R4M",
                        "Smart Relay Switch",
                        CommunicationProtocol.WI_FI,
                        WiringRequirement.NO_NEUTRAL,
                        2,
                        new BigDecimal("1000.00"),
                        new BigDecimal("2500.00"),
                        null,
                        null,
                        null,
                        null
                )
        );

        ExtractionResult input = new ExtractionResult(
                "ext_12345678",
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Ahmed Mahmoud", "+201000000000", "New Cairo", LocalDate.of(2026, 11, 20)),
                mismatchedFinancials,
                new ContractTerms("SUPPLY_AND_INSTALLATION", "1 year", "7 days", false, List.of()),
                mismatchedBom,
                List.of(SEMANTIC_WARNING),
                List.of()
        );

        ExtractionResult firstPass = FinancialGuardrailValidator.validateAndFlag(input);
        ExtractionResult secondPass = FinancialGuardrailValidator.validateAndFlag(firstPass);

        assertNotNull(firstPass);
        assertNotNull(secondPass);
        assertFalse(firstPass.financialCommitments().isMathVerified());
        assertFalse(secondPass.financialCommitments().isMathVerified());
        assertEquals(4, firstPass.warnings().size());
        assertEquals(firstPass.warnings(), secondPass.warnings());
        assertTrue(secondPass.warnings().contains(SEMANTIC_WARNING));
        assertEquals(3, secondPass.warnings().stream()
                .filter(w -> w.startsWith("Financial Guardrail Failure"))
                .count());
    }

    @Test
    void validateAndFlag_clearsStaleGuardrailWarningsWhenMathFixed() {
        FinancialCommitments correctedFinancials = new FinancialCommitments(
                new BigDecimal("2000.00"),
                null,
                true,
                null,
                new BigDecimal("2000.00"),
                "EGP",
                false,
                List.of(
                        new Installment("Deposit", new BigDecimal("1000.00"), "On signing"),
                        new Installment("Final", new BigDecimal("1000.00"), "On completion")
                )
        );

        List<BillOfMaterialsItem> correctedBom = List.of(
                new BillOfMaterialsItem(
                        DeviceCategory.SMART_LIGHTING,
                        "Sonoff Mini R4M",
                        "Smart Relay Switch",
                        CommunicationProtocol.WI_FI,
                        WiringRequirement.NO_NEUTRAL,
                        2,
                        new BigDecimal("1000.00"),
                        new BigDecimal("2000.00"),
                        null,
                        null,
                        null,
                        null
                )
        );

        ExtractionResult previouslyFailed = new ExtractionResult(
                "ext_87654321",
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Ahmed Mahmoud", "+201000000000", "New Cairo", LocalDate.of(2026, 11, 20)),
                correctedFinancials,
                new ContractTerms("SUPPLY_AND_INSTALLATION", "1 year", "7 days", false, List.of()),
                correctedBom,
                List.of(
                        SEMANTIC_WARNING,
                        "Financial Guardrail Failure (Installments): Sum of installments (1500.00) does not match total amount (2000.00).",
                        "Financial Guardrail Failure (Line Item): 'Sonoff Mini R4M' quantity (2) x unitPrice (1000.00) != lineTotal (2500.00).",
                        "Financial Guardrail Failure (BoM Total): Sum of itemized prices (2500.00) does not match stated subtotal/total (2000.00)."
                ),
                List.of()
        );

        ExtractionResult validated = FinancialGuardrailValidator.validateAndFlag(previouslyFailed);

        assertNotNull(validated);
        assertTrue(validated.financialCommitments().isMathVerified());
        assertEquals(List.of(SEMANTIC_WARNING), validated.warnings());
        assertTrue(validated.warnings().stream().noneMatch(w -> w.startsWith("Financial Guardrail Failure")));
    }

    @Test
    void validateAndFlag_capsClarificationQuestionsAtThree() {
        ExtractionResult input = new ExtractionResult(
                "ext_questions",
                ExtractionStatus.NEEDS_REVIEW,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(
                        new ClarificationQuestion("q_1", "billOfMaterials", "Q1?"),
                        new ClarificationQuestion("q_2", "billOfMaterials", "Q2?"),
                        new ClarificationQuestion("q_3", "financialCommitments", "Q3?"),
                        new ClarificationQuestion("q_4", "contractTerms", "Q4?")
                )
        );

        ExtractionResult validated = FinancialGuardrailValidator.validateAndFlag(input);

        assertNotNull(validated);
        assertEquals(3, validated.clarificationQuestions().size());
        assertEquals(List.of("q_1", "q_2", "q_3"), validated.clarificationQuestions().stream()
                .map(ClarificationQuestion::questionId)
                .toList());
    }
}
