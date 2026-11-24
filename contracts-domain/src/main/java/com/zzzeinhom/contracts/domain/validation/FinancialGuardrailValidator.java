package com.zzzeinhom.contracts.domain.validation;

import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClarificationQuestion;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.domain.model.Installment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class FinancialGuardrailValidator {

    private static final String GUARDRAIL_WARNING_PREFIX = "Financial Guardrail Failure";

    public static ExtractionResult validateAndFlag(ExtractionResult result) {
        if (result == null) {
            return null;
        }

        List<ClarificationQuestion> boundedQuestions = result.clarificationQuestions() == null
                ? List.of()
                : result.clarificationQuestions().stream().limit(3).toList();

        List<String> updatedWarnings = result.warnings() == null
                ? new ArrayList<>()
                : result.warnings().stream()
                        .filter(w -> w != null && !w.startsWith(GUARDRAIL_WARNING_PREFIX))
                        .collect(Collectors.toCollection(ArrayList::new));

        FinancialCommitments financials = result.financialCommitments();
        List<BillOfMaterialsItem> bom = result.billOfMaterials();
        boolean isMathVerified = true;

        // 1. Installments Verification
        if (financials != null && financials.totalAmount() != null
                && financials.installments() != null && !financials.installments().isEmpty()) {
            boolean hasNullInstallmentAmount = financials.installments().stream()
                    .anyMatch(installment -> installment == null || installment.amount() == null);

            BigDecimal calculatedTotal = financials.installments().stream()
                    .filter(Objects::nonNull)
                    .map(Installment::amount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (hasNullInstallmentAmount || calculatedTotal.compareTo(financials.totalAmount()) != 0) {
                isMathVerified = false;
                updatedWarnings.add(String.format(
                        GUARDRAIL_WARNING_PREFIX + " (Installments): Sum of installments (%s) does not match total amount (%s).",
                        calculatedTotal, financials.totalAmount()));
            }
        }

        // 2. Itemized BoM Line-Item Verification
        if (bom != null && !bom.isEmpty()) {
            for (BillOfMaterialsItem item : bom) {
                if (item != null && item.unitPrice() != null && item.lineTotal() != null) {
                    BigDecimal expectedLineTotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
                    if (expectedLineTotal.compareTo(item.lineTotal()) != 0) {
                        isMathVerified = false;
                        updatedWarnings.add(String.format(
                                GUARDRAIL_WARNING_PREFIX + " (Line Item): '%s' quantity (%d) x unitPrice (%s) != lineTotal (%s).",
                                item.rawName(), item.quantity(), item.unitPrice(), item.lineTotal()));
                    }
                }
            }
        }

        // 3. Itemized BoM Subtotal/Total Verification
        if (financials != null && bom != null && !bom.isEmpty()) {
            boolean allItemsPriced = bom.stream()
                    .allMatch(item -> item != null && (item.lineTotal() != null || item.unitPrice() != null));

            if (allItemsPriced) {
                BigDecimal bomSum = bom.stream()
                        .map(item -> item.lineTotal() != null
                                ? item.lineTotal()
                                : item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal referenceTotal = null;
                if (financials.subtotal() != null) {
                    referenceTotal = financials.subtotal();
                } else if (financials.discountAmount() == null && financials.vatAmount() == null) {
                    referenceTotal = financials.totalAmount();
                }

                if (referenceTotal != null && bomSum.compareTo(referenceTotal) != 0) {
                    isMathVerified = false;
                    updatedWarnings.add(String.format(
                            GUARDRAIL_WARNING_PREFIX + " (BoM Total): Sum of itemized prices (%s) does not match stated subtotal/total (%s).",
                            bomSum, referenceTotal));
                }
            }
        }

        FinancialCommitments verifiedFinancials = financials != null
                ? new FinancialCommitments(
                        financials.subtotal(),
                        financials.discountAmount(),
                        financials.isVatIncluded(),
                        financials.vatAmount(),
                        financials.totalAmount(),
                        financials.currency(),
                        isMathVerified,
                        financials.installments()
                )
                : null;

        return new ExtractionResult(
                result.extractionId(),
                result.status(),
                result.clientProfile(),
                verifiedFinancials,
                result.contractTerms(),
                result.billOfMaterials(),
                updatedWarnings,
                boundedQuestions
        );
    }
}
