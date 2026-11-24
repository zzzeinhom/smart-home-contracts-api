package com.zzzeinhom.contracts.mcp.dto;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;

import java.math.BigDecimal;

public record AggregatedBomItemDto(
        String normalizedDevice,
        DeviceCategory category,
        CommunicationProtocol protocol,
        WiringRequirement wiringRequirement,
        int totalQuantity,
        BigDecimal totalQuotedValue,
        int projectCount
) {}
