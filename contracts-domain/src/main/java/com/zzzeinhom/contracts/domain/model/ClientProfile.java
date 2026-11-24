package com.zzzeinhom.contracts.domain.model;

import java.time.LocalDate;

public record ClientProfile(
        String clientName,
        String phone,
        String projectLocation,
        LocalDate contractDate
) {}
