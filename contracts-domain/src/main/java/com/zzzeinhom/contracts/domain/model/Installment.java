package com.zzzeinhom.contracts.domain.model;

import java.math.BigDecimal;

public record Installment(
        String stage,
        BigDecimal amount,
        String dueCondition
) {}
