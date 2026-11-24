package com.zzzeinhom.contracts.service;

import com.zzzeinhom.contracts.ai.embedding.CatalogEmbeddingService;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CatalogNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(CatalogNormalizationService.class);
    private static final String PRICE_DRIFT_PREFIX = "Price Drift Alert:";

    private final CatalogEmbeddingService embeddingService;
    private final DeviceCatalogVectorRepository vectorRepository;
    private final double similarityThreshold;
    private final double priceDriftTolerancePercent;

    public CatalogNormalizationService(
            CatalogEmbeddingService embeddingService,
            DeviceCatalogVectorRepository vectorRepository,
            @Value("${app.catalog.similarity-threshold:0.80}") double similarityThreshold,
            @Value("${app.catalog.price-drift-tolerance-percent:10.0}") double priceDriftTolerancePercent) {
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
        this.similarityThreshold = similarityThreshold;
        this.priceDriftTolerancePercent = priceDriftTolerancePercent;
    }

    public ExtractionResult normalizeAndAudit(String tenantId, ExtractionResult result) {
        if (result == null || result.billOfMaterials() == null || result.billOfMaterials().isEmpty()) {
            return result;
        }

        List<String> updatedWarnings = result.warnings() == null
                ? new ArrayList<>()
                : result.warnings().stream()
                        .filter(w -> w != null && !w.startsWith(PRICE_DRIFT_PREFIX))
                        .collect(Collectors.toCollection(ArrayList::new));

        List<BillOfMaterialsItem> normalizedBom = new ArrayList<>(result.billOfMaterials().size());
        for (BillOfMaterialsItem item : result.billOfMaterials()) {
            if (item == null) {
                continue;
            }
            normalizedBom.add(normalizeItem(tenantId, item, updatedWarnings));
        }

        return new ExtractionResult(
                result.extractionId(),
                result.status(),
                result.clientProfile(),
                result.financialCommitments(),
                result.contractTerms(),
                normalizedBom,
                updatedWarnings,
                result.clarificationQuestions()
        );
    }

    private BillOfMaterialsItem normalizeItem(
            String tenantId, BillOfMaterialsItem item, List<String> updatedWarnings) {
        String query = nonNull(item.rawName())
                + " | " + nonNull(item.normalizedDevice())
                + " | " + item.category()
                + " | " + item.protocol();

        List<DeviceCatalogVectorRepository.CatalogMatch> matches;
        try {
            float[] vector = embeddingService.embed(query);
            String pgVectorLiteral = CatalogEmbeddingService.toPgVectorLiteral(vector);
            matches = vectorRepository.findTopMatches(tenantId, pgVectorLiteral, 1);
        } catch (Exception ex) {
            log.warn("Catalog lookup failed for tenant={} item='{}': {}",
                    tenantId, item.rawName(), ex.getMessage());
            return item;
        }

        if (matches == null || matches.isEmpty()) {
            return item;
        }

        DeviceCatalogVectorRepository.CatalogMatch match = matches.get(0);
        if (match.similarity() < similarityThreshold) {
            return item;
        }

        CommunicationProtocol protocol = (item.protocol() == CommunicationProtocol.OTHER
                && match.protocol() != null && match.protocol() != CommunicationProtocol.OTHER)
                ? match.protocol()
                : item.protocol();

        WiringRequirement wiring = (item.wiringRequirement() == WiringRequirement.UNSPECIFIED
                && match.wiringRequirement() != null && match.wiringRequirement() != WiringRequirement.UNSPECIFIED)
                ? match.wiringRequirement()
                : item.wiringRequirement();

        double roundedConfidence = Math.round(match.similarity() * 10000.0) / 10000.0;

        if (item.unitPrice() != null
                && match.referenceUnitPrice() != null
                && match.referenceUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal driftPercent = item.unitPrice()
                    .subtract(match.referenceUnitPrice())
                    .abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(match.referenceUnitPrice(), 2, RoundingMode.HALF_UP);

            if (driftPercent.compareTo(BigDecimal.valueOf(priceDriftTolerancePercent)) > 0) {
                updatedWarnings.add(String.format(
                        "%s '%s' quoted at %s vs catalog reference %s (%s%% variance).",
                        PRICE_DRIFT_PREFIX,
                        item.rawName(),
                        item.unitPrice(),
                        match.referenceUnitPrice(),
                        driftPercent));
            }
        }

        return new BillOfMaterialsItem(
                item.category(),
                item.rawName(),
                match.normalizedName(),
                protocol,
                wiring,
                item.quantity(),
                item.unitPrice(),
                item.lineTotal(),
                item.notes(),
                match.id(),
                roundedConfidence,
                match.referenceUnitPrice()
        );
    }

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }
}
