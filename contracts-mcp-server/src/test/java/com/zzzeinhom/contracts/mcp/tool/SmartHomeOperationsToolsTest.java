package com.zzzeinhom.contracts.mcp.tool;

import com.zzzeinhom.contracts.ai.embedding.CatalogEmbeddingService;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.mcp.dto.AggregatedBomItemDto;
import com.zzzeinhom.contracts.mcp.security.McpReactorTenantContextConfig;
import com.zzzeinhom.contracts.mcp.security.McpTenantContextHolder;
import com.zzzeinhom.contracts.mcp.security.McpTenantFilter;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmartHomeOperationsToolsTest {

    private final ExtractedDataRepository extractedDataRepository = mock(ExtractedDataRepository.class);
    private final CatalogEmbeddingService embeddingService = mock(CatalogEmbeddingService.class);
    private final DeviceCatalogVectorRepository vectorRepository = mock(DeviceCatalogVectorRepository.class);
    private final SmartHomeOperationsTools tools =
            new SmartHomeOperationsTools(extractedDataRepository, embeddingService, vectorRepository);

    @AfterEach
    void cleanUpContext() {
        McpTenantContextHolder.clear();
        McpTenantContextHolder.clearSessions();
    }

    @Test
    void tools_throwIllegalStateExceptionWhenTenantContextIsMissing() {
        McpTenantContextHolder.clear();

        IllegalStateException summaryEx = assertThrows(
                IllegalStateException.class, () -> tools.getClientProjectSummary("أحمد"));
        assertTrue(summaryEx.getMessage().contains("Missing X-Tenant-Id"));

        assertThrows(IllegalStateException.class, () -> tools.getPendingInstallments("ALL"));
        assertThrows(IllegalStateException.class, () -> tools.aggregateProcurementBom("ALL"));
        assertThrows(IllegalStateException.class, () -> tools.searchCompanyCatalog("sonoff", 5));

        verifyNoInteractions(extractedDataRepository, embeddingService, vectorRepository);
    }

    @Test
    void aggregateProcurementBom_sumsQuantitiesAndValuesAcrossTenantProjects() {
        McpTenantContextHolder.setTenantId("Tenant-Cairo");

        ExtractedDataEntity projectOne = buildExtraction(
                "ext_proj1",
                "tenant-cairo",
                List.of(
                        new BillOfMaterialsItem(
                                DeviceCategory.SMART_LIGHTING,
                                "مفتاح سونوف 1",
                                "Sonoff ZBMINI-L2",
                                CommunicationProtocol.ZIGBEE_WIRELESS,
                                WiringRequirement.NO_NEUTRAL,
                                4,
                                new BigDecimal("1000.00"),
                                new BigDecimal("4000.00"),
                                null, null, null, null
                        ),
                        new BillOfMaterialsItem(
                                DeviceCategory.CONTROLLERS_HUBS,
                                "Aqara Hub M3",
                                "Aqara Hub M3",
                                CommunicationProtocol.ZIGBEE_WIRELESS,
                                WiringRequirement.WITH_NEUTRAL,
                                1,
                                new BigDecimal("3500.00"),
                                new BigDecimal("3500.00"),
                                null, null, null, null
                        )
                )
        );

        ExtractedDataEntity projectTwo = buildExtraction(
                "ext_proj2",
                "tenant-cairo",
                List.of(
                        new BillOfMaterialsItem(
                                DeviceCategory.SMART_LIGHTING,
                                "مفتاح سونوف إضافي",
                                "sonoff zbmini-l2",
                                CommunicationProtocol.ZIGBEE_WIRELESS,
                                WiringRequirement.NO_NEUTRAL,
                                6,
                                new BigDecimal("1000.00"),
                                new BigDecimal("6000.00"),
                                null, null, null, null
                        )
                )
        );

        when(extractedDataRepository.findAllWithContractByTenantId("tenant-cairo"))
                .thenReturn(List.of(projectOne, projectTwo));

        List<AggregatedBomItemDto> aggregated = tools.aggregateProcurementBom("ALL");

        assertEquals(2, aggregated.size());

        AggregatedBomItemDto sonoff = aggregated.get(0);
        assertEquals(DeviceCategory.SMART_LIGHTING, sonoff.category());
        assertEquals(CommunicationProtocol.ZIGBEE_WIRELESS, sonoff.protocol());
        assertEquals(WiringRequirement.NO_NEUTRAL, sonoff.wiringRequirement());
        assertEquals(10, sonoff.totalQuantity());
        assertEquals(0, new BigDecimal("10000.00").compareTo(sonoff.totalQuotedValue()));
        assertEquals(2, sonoff.projectCount());

        AggregatedBomItemDto hub = aggregated.get(1);
        assertEquals("Aqara Hub M3", hub.normalizedDevice());
        assertEquals(1, hub.totalQuantity());
        assertEquals(0, new BigDecimal("3500.00").compareTo(hub.totalQuotedValue()));
        assertEquals(1, hub.projectCount());

        verify(extractedDataRepository).findAllWithContractByTenantId("tenant-cairo");
    }

    @Test
    void mcpTenantFilter_bindsSessionOnSseAndRestoresOnMessageThenClearsThreadLocal() throws Exception {
        McpTenantFilter filter = new McpTenantFilter();

        // 1. Initial GET /sse?sessionId=sess-123 with X-Tenant-Id header
        MockHttpServletRequest sseRequest = new MockHttpServletRequest("GET", "/sse");
        sseRequest.setParameter("sessionId", "sess-123");
        sseRequest.addHeader("X-Tenant-Id", "ACME-Egypt");
        MockHttpServletResponse sseResponse = new MockHttpServletResponse();

        AtomicReference<String> capturedOnSse = new AtomicReference<>();
        FilterChain sseChain = (req, res) -> capturedOnSse.set(McpTenantContextHolder.requireTenantId());

        filter.doFilter(sseRequest, sseResponse, sseChain);

        assertEquals("acme-egypt", capturedOnSse.get());
        assertThrows(IllegalStateException.class, McpTenantContextHolder::requireTenantId);

        // 2. Subsequent POST /mcp/message?sessionId=sess-123 WITHOUT X-Tenant-Id header
        MockHttpServletRequest msgRequest = new MockHttpServletRequest("POST", "/mcp/message");
        msgRequest.setParameter("sessionId", "sess-123");
        MockHttpServletResponse msgResponse = new MockHttpServletResponse();

        AtomicReference<String> capturedOnMessage = new AtomicReference<>();
        FilterChain msgChain = (req, res) -> capturedOnMessage.set(McpTenantContextHolder.requireTenantId());

        filter.doFilter(msgRequest, msgResponse, msgChain);

        assertEquals("acme-egypt", capturedOnMessage.get());
        assertThrows(IllegalStateException.class, McpTenantContextHolder::requireTenantId);
    }

    @Test
    void mcpTenantFilter_capturesServerGeneratedSseSessionIdFromResponseStream() throws Exception {
        McpTenantFilter filter = new McpTenantFilter();

        // 1. Initial GET /sse with X-Tenant-Id header and NO incoming sessionId query param
        MockHttpServletRequest sseRequest = new MockHttpServletRequest("GET", "/sse");
        sseRequest.addHeader("X-Tenant-Id", "Delta-Smart");
        MockHttpServletResponse sseResponse = new MockHttpServletResponse();

        FilterChain sseChain = (req, res) -> {
            assertEquals("delta-smart", McpTenantContextHolder.requireTenantId());
            res.getOutputStream().write("event:endpoint\ndata:/mcp/message?sessionId=srv-gen-999\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            res.getOutputStream().flush();
        };

        filter.doFilter(sseRequest, sseResponse, sseChain);
        assertThrows(IllegalStateException.class, McpTenantContextHolder::requireTenantId);

        // 2. Subsequent POST /mcp/message?sessionId=srv-gen-999 WITHOUT X-Tenant-Id header
        MockHttpServletRequest msgRequest = new MockHttpServletRequest("POST", "/mcp/message");
        msgRequest.setParameter("sessionId", "srv-gen-999");
        MockHttpServletResponse msgResponse = new MockHttpServletResponse();

        AtomicReference<String> capturedOnMessage = new AtomicReference<>();
        FilterChain msgChain = (req, res) -> capturedOnMessage.set(McpTenantContextHolder.requireTenantId());

        filter.doFilter(msgRequest, msgResponse, msgChain);

        assertEquals("delta-smart", capturedOnMessage.get());
        assertThrows(IllegalStateException.class, McpTenantContextHolder::requireTenantId);
    }

    @Test
    void reactorScheduleHook_propagatesTenantContextToBoundedElasticWithoutLeakage() {
        McpReactorTenantContextConfig config = new McpReactorTenantContextConfig();
        config.registerScheduleHook();
        try {
            String callerThread = Thread.currentThread().getName();
            McpTenantContextHolder.setTenantId("Smart-Power");

            String workerTenant = Mono.fromCallable(() -> {
                        assertNotEquals(callerThread, Thread.currentThread().getName());
                        return McpTenantContextHolder.requireTenantId();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            assertEquals("smart-power", workerTenant);

            McpTenantContextHolder.clear();
            String leakedTenant = Mono.fromCallable(McpTenantContextHolder::getTenantId)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            assertNull(leakedTenant);
        } finally {
            config.resetScheduleHook();
        }
    }

    private static ExtractedDataEntity buildExtraction(
            String extractionId, String tenantId, List<BillOfMaterialsItem> bom) {
        ExtractionResult result = new ExtractionResult(
                extractionId,
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Client", "+2010", "New Cairo", LocalDate.of(2026, 11, 23)),
                new FinancialCommitments(
                        new BigDecimal("10000.00"), null, true, null,
                        new BigDecimal("10000.00"), "EGP", true, List.of()),
                new ContractTerms("SUPPLY_AND_INSTALLATION", "1 year", "7 days", false, List.of()),
                bom,
                List.of(),
                List.of()
        );
        return new ExtractedDataEntity(extractionId, tenantId, result);
    }
}
