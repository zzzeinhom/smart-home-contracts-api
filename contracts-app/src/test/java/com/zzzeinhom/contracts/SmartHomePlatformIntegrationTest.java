package com.zzzeinhom.contracts;

import com.zzzeinhom.contracts.ai.quotation.CatalogCandidate;
import com.zzzeinhom.contracts.ai.quotation.GroundedRequirement;
import com.zzzeinhom.contracts.ai.quotation.QuotationGeneratorAgent;
import com.zzzeinhom.contracts.ai.quotation.SurveyDecomposition;
import com.zzzeinhom.contracts.ai.quotation.SurveyRequirementItem;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.dto.ApprovalRequest;
import com.zzzeinhom.contracts.dto.CatalogItemRequest;
import com.zzzeinhom.contracts.dto.CatalogItemResponse;
import com.zzzeinhom.contracts.dto.CatalogSearchMatchResponse;
import com.zzzeinhom.contracts.dto.IssueApiKeyRequest;
import com.zzzeinhom.contracts.dto.IssuedApiKeyResponse;
import com.zzzeinhom.contracts.dto.QuotationGenerateRequest;
import com.zzzeinhom.contracts.security.ApiKeyAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.client.enabled=false",
                "app.security.admin-key=ci-test-admin-secret"
        })
@Testcontainers
class SmartHomePlatformIntegrationTest {

    private static final String ADMIN_KEY = "ci-test-admin-secret";
    private static final int VECTOR_DIMENSIONS = 1024;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private QuotationGeneratorAgent quotationGeneratorAgent;

    @TestConfiguration
    static class TestMcpToolConfig {
        @Bean
        ToolCallbackProvider testToolCallbackProvider() {
            return ToolCallbackProvider.from();
        }
    }

    private final TestRestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    SmartHomePlatformIntegrationTest(
            TestRestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanDatabaseAndRedis() {
        jdbcTemplate.execute("TRUNCATE TABLE extracted_data, contracts, device_catalog, tenant_api_keys CASCADE");
        deleteKeysByPattern("auth:apikey:*");
        deleteKeysByPattern("job:*");
    }

    @Test
    void apiKeyLifecycle_enforcesAuthCachesInRedisAndBlocksRevokedKeys() {
        ResponseEntity<String> unauthenticated = restTemplate.getForEntity(
                "/api/v1/catalog/search?q=switch", String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        IssuedApiKeyResponse issued = issueTenantKey("tenant-alpha", "alpha-ci-key");
        assertThat(issued.tenantId()).isEqualTo("tenant-alpha");
        assertThat(issued.rawApiKey()).startsWith("shc_live_");

        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(embeddingResponse(unitVector(0)));

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("X-API-Key", issued.rawApiKey());
        tenantHeaders.set("X-Tenant-Id", "evil-tenant");
        tenantHeaders.setContentType(MediaType.APPLICATION_JSON);

        CatalogItemRequest itemRequest = new CatalogItemRequest(
                DeviceCategory.SMART_LIGHTING,
                "Tuya 3-Gang Wall Switch",
                "Tuya",
                "TY-3G",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.NO_NEUTRAL,
                new BigDecimal("1250.00"),
                "EGP",
                "3-gang smart switch");

        ResponseEntity<List<CatalogItemResponse>> createResponse = restTemplate.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(List.of(itemRequest), tenantHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).hasSize(1);
        assertThat(createResponse.getBody().get(0).tenantId()).isEqualTo("tenant-alpha");

        Integer evilCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_catalog WHERE tenant_id = 'evil-tenant'", Integer.class);
        assertThat(evilCount).isZero();

        String redisKey = "auth:apikey:" + ApiKeyAuthService.sha256Hex(issued.rawApiKey());
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("tenant-alpha");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Admin-Key", ADMIN_KEY);
        ResponseEntity<Void> revokeResponse = restTemplate.exchange(
                "/api/v1/admin/api-keys/" + issued.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders),
                Void.class);
        assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(redisTemplate.opsForValue().get(redisKey)).isNull();

        ResponseEntity<String> afterRevoke = restTemplate.exchange(
                "/api/v1/catalog/items",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                String.class);
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pgvectorCatalogSearch_ranksByCosineSimilarityAndIsolatesTenants() {
        IssuedApiKeyResponse alphaKey = issueTenantKey("tenant-alpha", "alpha-search");
        IssuedApiKeyResponse betaKey = issueTenantKey("tenant-beta", "beta-search");

        when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest req = invocation.getArgument(0);
            String text = req.getInstructions().isEmpty() ? "" : req.getInstructions().get(0);
            if (text.contains("Alpha Switch")) {
                return embeddingResponse(unitVector(0));
            }
            if (text.contains("Beta Switch")) {
                return embeddingResponse(unitVector(0));
            }
            if (text.contains("Beta Dimmer")) {
                return embeddingResponse(unitVector(1));
            }
            if (text.contains("alpha-query")) {
                return embeddingResponse(nearUnitVector(0, 1, 0.995f));
            }
            return embeddingResponse(unitVector(10));
        });

        upsertCatalogItem(alphaKey.rawApiKey(), new CatalogItemRequest(
                DeviceCategory.SMART_LIGHTING,
                "Alpha Switch",
                "Sonoff",
                "ALP-01",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.WITH_NEUTRAL,
                new BigDecimal("1400.00"),
                "EGP",
                "Alpha smart switch"));

        upsertCatalogItem(betaKey.rawApiKey(), new CatalogItemRequest(
                DeviceCategory.SMART_LIGHTING,
                "Beta Switch",
                "Aqara",
                "BET-01",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.WITH_NEUTRAL,
                new BigDecimal("2100.00"),
                "EGP",
                "Beta identical vector switch"));

        upsertCatalogItem(betaKey.rawApiKey(), new CatalogItemRequest(
                DeviceCategory.SMART_LIGHTING,
                "Beta Dimmer",
                "Aqara",
                "BET-02",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.NO_NEUTRAL,
                new BigDecimal("2500.00"),
                "EGP",
                "Beta dimmer"));

        HttpHeaders alphaHeaders = new HttpHeaders();
        alphaHeaders.set("X-API-Key", alphaKey.rawApiKey());
        ResponseEntity<List<CatalogSearchMatchResponse>> searchResponse = restTemplate.exchange(
                "/api/v1/catalog/search?q=alpha-query&limit=5",
                HttpMethod.GET,
                new HttpEntity<>(alphaHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<CatalogSearchMatchResponse> matches = searchResponse.getBody();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).normalizedName()).isEqualTo("Alpha Switch");
        assertThat(matches.get(0).similarity()).isGreaterThan(0.95);
        assertThat(matches).noneMatch(m -> m.normalizedName().contains("Beta"));
    }

    @Test
    void quotationGenerationAndHitlApproval_persistsDeterministicTotalsInPostgres() {
        IssuedApiKeyResponse alphaKey = issueTenantKey("tenant-alpha", "alpha-quotation");

        when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest req = invocation.getArgument(0);
            String text = req.getInstructions().isEmpty() ? "" : req.getInstructions().get(0);
            if (text.contains("Tuya 3-Gang")) {
                return embeddingResponse(unitVector(0));
            }
            return embeddingResponse(unitVector(5));
        });

        upsertCatalogItem(alphaKey.rawApiKey(), new CatalogItemRequest(
                DeviceCategory.SMART_LIGHTING,
                "Tuya 3-Gang Switch",
                "Tuya",
                "TY-3G",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.WITH_NEUTRAL,
                new BigDecimal("1500.00"),
                "EGP",
                "Standard 3-gang smart switch"));

        when(quotationGeneratorAgent.decomposeSurvey(anyString())).thenReturn(new SurveyDecomposition(
                new ClientProfile("Eng. Khaled", "+201001234567", "New Cairo", LocalDate.of(2026, 11, 24)),
                new ContractTerms("SUPPLY_AND_INSTALLATION", "2 years", "10 days", false, List.of("14% VAT applies")),
                List.of(
                        new SurveyRequirementItem(
                                "Tuya 3-Gang",
                                DeviceCategory.SMART_LIGHTING,
                                WiringRequirement.WITH_NEUTRAL,
                                4,
                                "Reception"),
                        new SurveyRequirementItem(
                                "Custom Rare Sensor",
                                DeviceCategory.SENSORS_SAFETY,
                                WiringRequirement.WIRELESS_BATTERY,
                                1,
                                "Garden gate")),
                List.of()));

        when(quotationGeneratorAgent.assembleGroundedBom(anyList())).thenAnswer(invocation -> {
            List<GroundedRequirement> grounded = invocation.getArgument(0);
            List<BillOfMaterialsItem> items = new ArrayList<>();
            for (GroundedRequirement entry : grounded) {
                SurveyRequirementItem req = entry.requirement();
                if (!entry.candidates().isEmpty()) {
                    CatalogCandidate candidate = entry.candidates().get(0);
                    items.add(new BillOfMaterialsItem(
                            req.category(),
                            req.requirementQuery(),
                            candidate.normalizedName(),
                            candidate.protocol(),
                            candidate.wiringRequirement(),
                            req.quantity(),
                            null,
                            null,
                            req.roomOrNotes(),
                            candidate.id(),
                            candidate.similarity(),
                            candidate.referenceUnitPrice()));
                } else {
                    items.add(new BillOfMaterialsItem(
                            req.category(),
                            req.requirementQuery(),
                            req.requirementQuery(),
                            CommunicationProtocol.OTHER,
                            req.wiringRequirement(),
                            req.quantity(),
                            null,
                            null,
                            req.roomOrNotes(),
                            null,
                            null,
                            null));
                }
            }
            return items;
        });

        HttpHeaders alphaHeaders = new HttpHeaders();
        alphaHeaders.set("X-API-Key", alphaKey.rawApiKey());
        alphaHeaders.setContentType(MediaType.APPLICATION_JSON);

        QuotationGenerateRequest quoteRequest = new QuotationGenerateRequest(
                "Need 4 Tuya 3-Gang switches in reception and 1 custom rare garden sensor",
                "Eng. Khaled",
                "+201001234567",
                "New Cairo",
                true,
                null,
                new BigDecimal("10"),
                List.of(50, 30, 20));

        ResponseEntity<ExtractionResult> quoteResponse = restTemplate.exchange(
                "/api/v1/quotations/generate",
                HttpMethod.POST,
                new HttpEntity<>(quoteRequest, alphaHeaders),
                ExtractionResult.class);

        assertThat(quoteResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ExtractionResult created = quoteResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(ExtractionStatus.NEEDS_REVIEW);
        assertThat(created.billOfMaterials()).hasSize(3);

        BillOfMaterialsItem cataloguedItem = created.billOfMaterials().get(0);
        assertThat(cataloguedItem.normalizedDevice()).isEqualTo("Tuya 3-Gang Switch");
        assertThat(cataloguedItem.unitPrice()).isEqualByComparingTo("1500.00");
        assertThat(cataloguedItem.lineTotal()).isEqualByComparingTo("6000.00");

        BillOfMaterialsItem uncataloguedItem = created.billOfMaterials().get(1);
        assertThat(uncataloguedItem.unitPrice()).isNull();
        assertThat(uncataloguedItem.matchedCatalogId()).isNull();

        BillOfMaterialsItem installationItem = created.billOfMaterials().get(2);
        assertThat(installationItem.normalizedDevice()).isEqualTo("Installation, Testing & Commissioning");
        assertThat(installationItem.lineTotal()).isEqualByComparingTo("600.00");

        assertThat(created.warnings()).anyMatch(w -> w.contains("Uncatalogued Item:"));
        assertThat(created.financialCommitments().isMathVerified()).isTrue();
        // Hardware 6000.00 + 10% installation (600.00) = 6600.00; + 14% VAT (924.00) = 7524.00
        assertThat(created.financialCommitments().totalAmount()).isEqualByComparingTo("7524.00");
        assertThat(created.financialCommitments().installments()).hasSize(3);

        ResponseEntity<ExtractionResult> approveResponse = restTemplate.exchange(
                "/api/v1/contracts/extract/" + created.extractionId() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(new ApprovalRequest("eng.omar"), alphaHeaders),
                ExtractionResult.class);

        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveResponse.getBody()).isNotNull();
        assertThat(approveResponse.getBody().status()).isEqualTo(ExtractionStatus.APPROVED);

        String reviewStatus = jdbcTemplate.queryForObject(
                "SELECT review_status FROM extracted_data WHERE extraction_id = ?",
                String.class,
                created.extractionId());
        assertThat(reviewStatus).isEqualTo("APPROVED");
    }

    private IssuedApiKeyResponse issueTenantKey(String tenantId, String keyName) {
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Admin-Key", ADMIN_KEY);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<IssuedApiKeyResponse> response = restTemplate.exchange(
                "/api/v1/admin/api-keys",
                HttpMethod.POST,
                new HttpEntity<>(new IssueApiKeyRequest(tenantId, keyName, null), adminHeaders),
                IssuedApiKeyResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void upsertCatalogItem(String rawApiKey, CatalogItemRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", rawApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<List<CatalogItemResponse>> response = restTemplate.exchange(
                "/api/v1/catalog/items",
                HttpMethod.POST,
                new HttpEntity<>(List.of(request), headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static EmbeddingResponse embeddingResponse(float[] vector) {
        return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
    }

    private static float[] unitVector(int hotIndex) {
        float[] vec = new float[VECTOR_DIMENSIONS];
        vec[hotIndex] = 1.0f;
        return vec;
    }

    private static float[] nearUnitVector(int primaryIndex, int secondaryIndex, float primaryWeight) {
        float[] vec = new float[VECTOR_DIMENSIONS];
        double remaining = Math.sqrt(Math.max(0.0, 1.0 - (double) primaryWeight * primaryWeight));
        vec[primaryIndex] = primaryWeight;
        vec[secondaryIndex] = (float) remaining;
        return vec;
    }
}
