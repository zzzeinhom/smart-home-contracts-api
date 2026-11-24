package com.zzzeinhom.contracts.service;

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
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogNormalizationServiceTest {

    private static final String SEMANTIC_WARNING = "Cabling length unspecified.";

    @Test
    void normalizeAndAudit_matchesCatalogUpgradesFieldsAndFlagsPriceDriftIdempotently() {
        CatalogEmbeddingService embeddingService = mock(CatalogEmbeddingService.class);
        DeviceCatalogVectorRepository vectorRepository = mock(DeviceCatalogVectorRepository.class);

        float[] dummyVec = new float[]{0.5f, 0.5f, 0.5f, 0.5f};
        when(embeddingService.embed(anyString())).thenReturn(dummyVec);

        UUID catalogId = UUID.randomUUID();
        DeviceCatalogVectorRepository.CatalogMatch match = new DeviceCatalogVectorRepository.CatalogMatch(
                catalogId,
                DeviceCategory.SMART_LIGHTING,
                "Sonoff ZBMINI-L2 No-Neutral Smart Relay",
                "Sonoff",
                "ZBMINI-L2",
                CommunicationProtocol.ZIGBEE_WIRELESS,
                WiringRequirement.NO_NEUTRAL,
                new BigDecimal("1000.00"),
                "EGP",
                0.9125
        );
        when(vectorRepository.findTopMatches(eq("tenant-a"), anyString(), anyInt()))
                .thenReturn(List.of(match));

        CatalogNormalizationService service = new CatalogNormalizationService(
                embeddingService, vectorRepository, 0.80, 10.0);

        BillOfMaterialsItem rawItem = new BillOfMaterialsItem(
                DeviceCategory.SMART_LIGHTING,
                "مفتاح سونوف بدون نيوترال",
                "Sonoff Switch",
                CommunicationProtocol.OTHER,
                WiringRequirement.UNSPECIFIED,
                4,
                new BigDecimal("1250.00"),
                new BigDecimal("5000.00"),
                "Living room",
                null,
                null,
                null
        );

        ExtractionResult input = new ExtractionResult(
                "ext_norm_1",
                ExtractionStatus.NEEDS_REVIEW,
                new ClientProfile("Client", "+2010", "Cairo", LocalDate.of(2026, 11, 20)),
                new FinancialCommitments(
                        new BigDecimal("5000.00"), null, true, null,
                        new BigDecimal("5000.00"), "EGP", true, List.of()),
                new ContractTerms("SUPPLY_AND_INSTALLATION", "1 year", "7 days", false, List.of()),
                List.of(rawItem),
                List.of(SEMANTIC_WARNING),
                List.of()
        );

        ExtractionResult firstPass = service.normalizeAndAudit("tenant-a", input);
        ExtractionResult secondPass = service.normalizeAndAudit("tenant-a", firstPass);

        assertNotNull(firstPass);
        BillOfMaterialsItem enriched = firstPass.billOfMaterials().get(0);
        assertEquals("مفتاح سونوف بدون نيوترال", enriched.rawName());
        assertEquals("Sonoff ZBMINI-L2 No-Neutral Smart Relay", enriched.normalizedDevice());
        assertEquals(CommunicationProtocol.ZIGBEE_WIRELESS, enriched.protocol());
        assertEquals(WiringRequirement.NO_NEUTRAL, enriched.wiringRequirement());
        assertEquals(catalogId, enriched.matchedCatalogId());
        assertEquals(0.9125, enriched.matchConfidence());
        assertEquals(new BigDecimal("1000.00"), enriched.catalogReferencePrice());

        assertEquals(2, firstPass.warnings().size());
        assertTrue(firstPass.warnings().contains(SEMANTIC_WARNING));
        assertTrue(firstPass.warnings().stream().anyMatch(w -> w.startsWith("Price Drift Alert:")));
        assertEquals(firstPass.warnings(), secondPass.warnings());
        assertEquals(1L, secondPass.warnings().stream()
                .filter(w -> w.startsWith("Price Drift Alert:")).count());
    }
}
