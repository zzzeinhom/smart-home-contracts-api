package com.zzzeinhom.contracts.persistence.repository;

import com.zzzeinhom.contracts.persistence.entity.TenantApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantApiKeyRepository extends JpaRepository<TenantApiKeyEntity, UUID> {

    Optional<TenantApiKeyEntity> findByKeyHash(String keyHash);

    boolean existsByKeyHash(String keyHash);
}
