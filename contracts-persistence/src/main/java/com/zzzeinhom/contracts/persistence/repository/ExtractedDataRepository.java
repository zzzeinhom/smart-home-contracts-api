package com.zzzeinhom.contracts.persistence.repository;

import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExtractedDataRepository extends JpaRepository<ExtractedDataEntity, String> {

    Optional<ExtractedDataEntity> findByExtractionIdAndTenantId(String extractionId, String tenantId);

    @Query("SELECT e FROM ExtractedDataEntity e JOIN FETCH e.contract WHERE e.tenantId = :tenantId ORDER BY e.createdAt DESC")
    List<ExtractedDataEntity> findAllWithContractByTenantId(@Param("tenantId") String tenantId);
}
