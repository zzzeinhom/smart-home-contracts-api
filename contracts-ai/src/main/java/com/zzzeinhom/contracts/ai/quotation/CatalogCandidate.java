package com.zzzeinhom.contracts.ai.quotation;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogCandidate(
        UUID id,
        String normalizedName,
        String brand,
        String modelCode,
        DeviceCategory category,
        CommunicationProtocol protocol,
        WiringRequirement wiringRequirement,
        BigDecimal referenceUnitPrice,
        double similarity
) {}
