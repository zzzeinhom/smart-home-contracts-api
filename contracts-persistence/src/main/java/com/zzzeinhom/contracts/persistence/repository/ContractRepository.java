package com.zzzeinhom.contracts.persistence.repository;

import com.zzzeinhom.contracts.persistence.entity.ContractEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContractRepository extends JpaRepository<ContractEntity, String> {

    Optional<ContractEntity> findByIdAndTenantId(String id, String tenantId);
}
