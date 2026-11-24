package com.zzzeinhom.contracts.dto;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CatalogItemRequest(
        @NotNull(message = "category is required")
        DeviceCategory category,
        @NotBlank(message = "normalizedName is required")
        String normalizedName,
        String brand,
        String modelCode,
        @NotNull(message = "protocol is required")
        CommunicationProtocol protocol,
        WiringRequirement wiringRequirement,
        BigDecimal referenceUnitPrice,
        String currency,
        String description
) {}
