package com.zzzeinhom.contracts.ai.quotation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;

public record SurveyRequirementItem(
        @JsonPropertyDescription("Concise technical search query describing the requested device or capability")
        String requirementQuery,
        DeviceCategory category,
        WiringRequirement wiringRequirement,
        int quantity,
        @JsonPropertyDescription("Room name, installation notes, or context preserved in the survey language")
        String roomOrNotes
) {}
