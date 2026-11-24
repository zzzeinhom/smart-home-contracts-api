package com.zzzeinhom.contracts.domain.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record ContractTerms(
        @JsonPropertyDescription("Project scope classification: SUPPLY_ONLY, SUPPLY_AND_INSTALLATION, PROGRAMMING_AND_COMMISSIONING, or UNSPECIFIED")
        String scopeType,
        @JsonPropertyDescription("Hardware warranty period if stated (e.g., '1 year against manufacturing defects'), otherwise null")
        String warrantyPeriod,
        @JsonPropertyDescription("Quotation validity duration if stated (e.g., '7 days', '10 أيام'), otherwise null")
        String quoteValidity,
        @JsonPropertyDescription("True if contract mentions price adjustment based on USD/EGP exchange rate fluctuations")
        Boolean priceLinkedToExchangeRate,
        @JsonPropertyDescription("Explicitly excluded civil/electrical works (e.g., chiseling/تكسير, conduits/خراطيم, cables, racks); empty list if none")
        List<String> exclusions
) {}
