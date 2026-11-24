package com.zzzeinhom.contracts.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BillOfMaterialsItem(
        DeviceCategory category,
        String rawName,
        String normalizedDevice,
        @JsonPropertyDescription("Standardized communication protocol enum; use KNX for wired bus actuators, IR_RF for AC/TV IR blasters, NOT_APPLICABLE for passive materials like cables")
        CommunicationProtocol protocol,
        @JsonPropertyDescription("Electrical wiring requirement: WITH_NEUTRAL, NO_NEUTRAL (e.g., Sonoff Mini R4M or capacitor-assisted switches in Egyptian Magic wall boxes), DIN_RAIL (breakers/actuators), WIRELESS_BATTERY, NOT_APPLICABLE, or UNSPECIFIED")
        WiringRequirement wiringRequirement,
        int quantity,
        @JsonPropertyDescription("Unit price if explicitly listed in the quote; otherwise null")
        BigDecimal unitPrice,
        @JsonPropertyDescription("Total line price (quantity * unitPrice) if listed in the quote; otherwise null")
        BigDecimal lineTotal,
        String notes,
        @JsonPropertyDescription("Leave null during extraction; populated by backend catalog matching")
        UUID matchedCatalogId,
        @JsonPropertyDescription("Leave null during extraction; populated by backend catalog matching")
        Double matchConfidence,
        @JsonPropertyDescription("Leave null during extraction; populated by backend catalog matching")
        BigDecimal catalogReferencePrice
) {}
