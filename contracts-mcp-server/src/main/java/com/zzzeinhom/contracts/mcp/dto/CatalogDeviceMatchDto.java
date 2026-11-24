package com.zzzeinhom.contracts.mcp.dto;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogDeviceMatchDto(
        UUID id,
        DeviceCategory category,
        String normalizedName,
        String brand,
        String modelCode,
        CommunicationProtocol protocol,
        WiringRequirement wiringRequirement,
        BigDecimal referenceUnitPrice,
        String currency,
        double similarity
) {}
