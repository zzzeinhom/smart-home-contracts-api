package com.zzzeinhom.contracts.dto;

import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;

public record JobStatusResponse(
        String jobId,
        ExtractionStatus status,
        String errorMessage,
        ExtractionResult result
) {}
