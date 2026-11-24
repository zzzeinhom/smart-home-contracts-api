package com.zzzeinhom.contracts.dto;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogSearchMatchResponse(
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
) {
    public static CatalogSearchMatchResponse from(DeviceCatalogVectorRepository.CatalogMatch match) {
        return new CatalogSearchMatchResponse(
                match.id(),
                match.category(),
                match.normalizedName(),
                match.brand(),
                match.modelCode(),
                match.protocol(),
                match.wiringRequirement(),
                match.referenceUnitPrice(),
                match.currency(),
                match.similarity()
        );
    }
}
