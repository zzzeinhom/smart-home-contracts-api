package com.zzzeinhom.contracts.persistence.repository;

import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class DeviceCatalogVectorRepository {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalogVectorRepository.class);

    private static final String UPDATE_EMBEDDING_SQL = """
            UPDATE device_catalog
            SET embedding = CAST(? AS vector)
            WHERE id = ? AND tenant_id = ?
            """;

    private static final String FIND_MISSING_EMBEDDING_IDS_SQL = """
            SELECT id
            FROM device_catalog
            WHERE tenant_id = ?
              AND embedding IS NULL
            """;

    private static final String FIND_TOP_MATCHES_SQL = """
            SELECT id, category, normalized_name, brand, model_code, protocol, wiring_requirement,
                   reference_unit_price, currency,
                   1 - (embedding <=> CAST(? AS vector)) AS similarity
            FROM device_catalog
            WHERE tenant_id = ?
              AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public DeviceCatalogVectorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void updateEmbedding(UUID id, String tenantId, String pgVectorLiteral) {
        int rowsUpdated = jdbcTemplate.update(UPDATE_EMBEDDING_SQL, pgVectorLiteral, id, tenantId);
        if (rowsUpdated != 1) {
            log.error("Expected 1 row updated when storing embedding for tenant={} id={}, actual={}",
                    tenantId, id, rowsUpdated);
            throw new IllegalStateException("Failed to persist embedding vector for catalog item: " + id);
        }
    }

    public List<UUID> findIdsWithMissingEmbedding(String tenantId) {
        return jdbcTemplate.query(
                FIND_MISSING_EMBEDDING_IDS_SQL,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId
        );
    }

    public List<CatalogMatch> findTopMatches(String tenantId, String pgVectorLiteral, int limit) {
        int effectiveLimit = limit > 0 ? limit : 1;
        return jdbcTemplate.query(
                FIND_TOP_MATCHES_SQL,
                (rs, rowNum) -> new CatalogMatch(
                        rs.getObject("id", UUID.class),
                        DeviceCategory.valueOf(rs.getString("category")),
                        rs.getString("normalized_name"),
                        rs.getString("brand"),
                        rs.getString("model_code"),
                        CommunicationProtocol.valueOf(rs.getString("protocol")),
                        WiringRequirement.valueOf(rs.getString("wiring_requirement")),
                        rs.getBigDecimal("reference_unit_price"),
                        rs.getString("currency"),
                        rs.getDouble("similarity")
                ),
                pgVectorLiteral,
                tenantId,
                pgVectorLiteral,
                effectiveLimit
        );
    }

    public record CatalogMatch(
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
    ) {}
}
