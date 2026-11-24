package com.zzzeinhom.contracts.persistence.repository;

import com.zzzeinhom.contracts.persistence.entity.DeviceCatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceCatalogRepository extends JpaRepository<DeviceCatalogEntity, UUID> {

    List<DeviceCatalogEntity> findAllByTenantIdOrderByCategoryAscNormalizedNameAsc(String tenantId);

    Optional<DeviceCatalogEntity> findByTenantIdAndNormalizedName(String tenantId, String normalizedName);

    void deleteByIdAndTenantId(UUID id, String tenantId);
}
