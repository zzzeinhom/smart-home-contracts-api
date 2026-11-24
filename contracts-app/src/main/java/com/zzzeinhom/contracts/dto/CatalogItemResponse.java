package com.zzzeinhom.contracts.dto;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.persistence.entity.DeviceCatalogEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CatalogItemResponse(
        UUID id,
        String tenantId,
        DeviceCategory category,
        String normalizedName,
        String brand,
        String modelCode,
        CommunicationProtocol protocol,
        WiringRequirement wiringRequirement,
        BigDecimal referenceUnitPrice,
        String currency,
        String description,
        Instant createdAt
) {
    public static CatalogItemResponse from(DeviceCatalogEntity entity) {
        return new CatalogItemResponse(
                entity.getId(),
                entity.getTenantId(),
                entity.getCategory(),
                entity.getNormalizedName(),
                entity.getBrand(),
                entity.getModelCode(),
                entity.getProtocol(),
                entity.getWiringRequirement(),
                entity.getReferenceUnitPrice(),
                entity.getCurrency(),
                entity.getDescription(),
                entity.getCreatedAt()
        );
    }
}
