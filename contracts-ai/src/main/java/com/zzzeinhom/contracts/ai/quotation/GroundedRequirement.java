package com.zzzeinhom.contracts.ai.quotation;

import java.util.List;

public record GroundedRequirement(
        SurveyRequirementItem requirement,
        List<CatalogCandidate> candidates
) {}
