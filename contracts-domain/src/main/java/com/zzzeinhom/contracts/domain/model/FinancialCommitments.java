package com.zzzeinhom.contracts.domain.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.math.BigDecimal;
import java.util.List;

public record FinancialCommitments(
        @JsonPropertyDescription("Subtotal before discount and VAT if stated; otherwise null")
        BigDecimal subtotal,
        @JsonPropertyDescription("Discount amount if stated; otherwise null")
        BigDecimal discountAmount,
        @JsonPropertyDescription("True if 14% Egyptian VAT is explicitly included in totalAmount, false if explicitly excluded, null if unmentioned")
        Boolean isVatIncluded,
        @JsonPropertyDescription("Explicit VAT amount if stated; otherwise null")
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        @JsonPropertyDescription("ISO currency code, defaulting to EGP for Egyptian quotes if unspecified")
        String currency,
        boolean isMathVerified,
        List<Installment> installments
) {}
