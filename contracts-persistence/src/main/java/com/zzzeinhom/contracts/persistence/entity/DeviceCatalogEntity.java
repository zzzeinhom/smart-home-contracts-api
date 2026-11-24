package com.zzzeinhom.contracts.persistence.entity;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_catalog")
public class DeviceCatalogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 50, nullable = false)
    private DeviceCategory category;

    @Column(name = "normalized_name", length = 255, nullable = false)
    private String normalizedName;

    @Column(name = "brand", length = 100)
    private String brand;

    @Column(name = "model_code", length = 100)
    private String modelCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", length = 50, nullable = false)
    private CommunicationProtocol protocol;

    @Enumerated(EnumType.STRING)
    @Column(name = "wiring_requirement", length = 50, nullable = false)
    private WiringRequirement wiringRequirement;

    @Column(name = "reference_unit_price", precision = 12, scale = 2)
    private BigDecimal referenceUnitPrice;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeviceCatalogEntity() {
    }

    public DeviceCatalogEntity(
            String tenantId,
            DeviceCategory category,
            String normalizedName,
            String brand,
            String modelCode,
            CommunicationProtocol protocol,
            WiringRequirement wiringRequirement,
            BigDecimal referenceUnitPrice,
            String currency,
            String description) {
        Instant now = Instant.now();
        this.tenantId = tenantId;
        this.category = category;
        this.normalizedName = normalizedName;
        this.brand = brand;
        this.modelCode = modelCode;
        this.protocol = protocol;
        this.wiringRequirement = wiringRequirement != null ? wiringRequirement : WiringRequirement.UNSPECIFIED;
        this.referenceUnitPrice = referenceUnitPrice;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim() : "EGP";
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateDetails(
            DeviceCategory category,
            String brand,
            String modelCode,
            CommunicationProtocol protocol,
            WiringRequirement wiringRequirement,
            BigDecimal referenceUnitPrice,
            String currency,
            String description) {
        this.category = category;
        this.brand = brand;
        this.modelCode = modelCode;
        this.protocol = protocol;
        this.wiringRequirement = wiringRequirement != null ? wiringRequirement : WiringRequirement.UNSPECIFIED;
        this.referenceUnitPrice = referenceUnitPrice;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim() : "EGP";
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public DeviceCategory getCategory() {
        return category;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getBrand() {
        return brand;
    }

    public String getModelCode() {
        return modelCode;
    }

    public CommunicationProtocol getProtocol() {
        return protocol;
    }

    public WiringRequirement getWiringRequirement() {
        return wiringRequirement;
    }

    public BigDecimal getReferenceUnitPrice() {
        return referenceUnitPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
