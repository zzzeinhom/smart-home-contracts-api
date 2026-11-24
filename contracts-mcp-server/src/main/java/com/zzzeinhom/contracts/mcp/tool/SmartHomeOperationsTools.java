package com.zzzeinhom.contracts.mcp.tool;

import com.zzzeinhom.contracts.ai.embedding.CatalogEmbeddingService;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.mcp.dto.AggregatedBomItemDto;
import com.zzzeinhom.contracts.mcp.dto.CatalogDeviceMatchDto;
import com.zzzeinhom.contracts.mcp.dto.ClientProjectSummaryDto;
import com.zzzeinhom.contracts.mcp.dto.PendingInstallmentItemDto;
import com.zzzeinhom.contracts.mcp.security.McpTenantContextHolder;
import com.zzzeinhom.contracts.persistence.entity.ContractEntity;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SmartHomeOperationsTools {

    private final ExtractedDataRepository extractedDataRepository;
    private final CatalogEmbeddingService embeddingService;
    private final DeviceCatalogVectorRepository vectorRepository;

    public SmartHomeOperationsTools(
            ExtractedDataRepository extractedDataRepository,
            CatalogEmbeddingService embeddingService,
            DeviceCatalogVectorRepository vectorRepository) {
        this.extractedDataRepository = extractedDataRepository;
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
    }

    @Tool(description = "Search and retrieve smart home contract or quotation details, location, financial totals, commercial exclusions, and bill of materials summary for a client name, phone number, or extraction ID.")
    public List<ClientProjectSummaryDto> getClientProjectSummary(
            @ToolParam(description = "Client name, partial Arabic/English name, phone number, or extraction/quotation ID") String clientQuery) {
        String tenantId = McpTenantContextHolder.requireTenantId();
        if (clientQuery == null || clientQuery.isBlank()) {
            return List.of();
        }
        String needle = clientQuery.trim().toLowerCase(Locale.ROOT);

        return extractedDataRepository.findAllWithContractByTenantId(tenantId).stream()
                .filter(entity -> matchesClientQuery(entity, needle))
                .map(SmartHomeOperationsTools::toClientProjectSummary)
                .toList();
    }

    @Tool(description = "List all active or unapproved smart home contracts and quotations with their installment schedules, total amounts, and any unverified math or price drift warnings.")
    public List<PendingInstallmentItemDto> getPendingInstallments(
            @ToolParam(description = "Optional review status filter: ALL, NEEDS_REVIEW, or APPROVED", required = false) String statusFilter) {
        String tenantId = McpTenantContextHolder.requireTenantId();
        ExtractionStatus targetStatus = parseStatusFilter(statusFilter);

        return extractedDataRepository.findAllWithContractByTenantId(tenantId).stream()
                .filter(entity -> targetStatus == null || entity.getReviewStatus() == targetStatus)
                .map(SmartHomeOperationsTools::toPendingInstallmentItem)
                .toList();
    }

    @Tool(description = "Aggregate total hardware device quantities across smart home projects for supplier procurement, grouped by normalized device name, category, protocol, and wiring requirement.")
    public List<AggregatedBomItemDto> aggregateProcurementBom(
            @ToolParam(description = "Project status filter: ALL, APPROVED, or NEEDS_REVIEW (defaults to ALL)", required = false) String statusFilter) {
        String tenantId = McpTenantContextHolder.requireTenantId();
        ExtractionStatus targetStatus = parseStatusFilter(statusFilter);

        List<ExtractedDataEntity> records = extractedDataRepository.findAllWithContractByTenantId(tenantId).stream()
                .filter(entity -> targetStatus == null || entity.getReviewStatus() == targetStatus)
                .toList();

        Map<BomGroupKey, BomAccumulator> grouped = new HashMap<>();
        for (ExtractedDataEntity entity : records) {
            ExtractionResult payload = entity.getPayload();
            if (payload == null || payload.billOfMaterials() == null) {
                continue;
            }
            for (BillOfMaterialsItem item : payload.billOfMaterials()) {
                if (item == null) {
                    continue;
                }
                String displayName = (item.normalizedDevice() != null && !item.normalizedDevice().isBlank())
                        ? item.normalizedDevice().trim()
                        : (item.rawName() != null && !item.rawName().isBlank() ? item.rawName().trim() : "Unnamed Device");
                BomGroupKey key = new BomGroupKey(
                        displayName.toLowerCase(Locale.ROOT),
                        item.category(),
                        item.protocol(),
                        item.wiringRequirement()
                );
                BomAccumulator accumulator = grouped.computeIfAbsent(key, k -> new BomAccumulator(displayName));
                int qty = Math.max(0, item.quantity());
                accumulator.totalQuantity += qty;
                accumulator.totalQuotedValue = accumulator.totalQuotedValue.add(resolveQuotedValue(item, qty));
                accumulator.projectIds.add(entity.getExtractionId());
            }
        }

        return grouped.entrySet().stream()
                .map(entry -> {
                    BomGroupKey key = entry.getKey();
                    BomAccumulator acc = entry.getValue();
                    return new AggregatedBomItemDto(
                            acc.displayName,
                            key.category(),
                            key.protocol(),
                            key.wiringRequirement(),
                            acc.totalQuantity,
                            acc.totalQuotedValue,
                            acc.projectIds.size()
                    );
                })
                .sorted(Comparator.comparingInt(AggregatedBomItemDto::totalQuantity).reversed()
                        .thenComparing(AggregatedBomItemDto::normalizedDevice, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Tool(description = "Semantically search the company's smart home device catalog in Arabic or English for reference unit prices, model codes, communication protocols, and neutral/no-neutral wiring specs.")
    public List<CatalogDeviceMatchDto> searchCompanyCatalog(
            @ToolParam(description = "Device description or query in Arabic or English") String query,
            @ToolParam(description = "Maximum number of results to return (1-10, default 5)", required = false) Integer limit) {
        String tenantId = McpTenantContextHolder.requireTenantId();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Catalog search query must not be blank.");
        }
        int boundedLimit = limit == null ? 5 : Math.max(1, Math.min(10, limit));
        float[] vector = embeddingService.embed(query.trim());
        String pgVectorLiteral = CatalogEmbeddingService.toPgVectorLiteral(vector);

        return vectorRepository.findTopMatches(tenantId, pgVectorLiteral, boundedLimit).stream()
                .map(match -> new CatalogDeviceMatchDto(
                        match.id(),
                        match.category(),
                        match.normalizedName(),
                        match.brand(),
                        match.modelCode(),
                        match.protocol(),
                        match.wiringRequirement(),
                        match.referenceUnitPrice(),
                        match.currency(),
                        Math.round(match.similarity() * 10000.0) / 10000.0
                ))
                .toList();
    }

    private static boolean matchesClientQuery(ExtractedDataEntity entity, String needle) {
        if (containsIgnoreCase(entity.getExtractionId(), needle)) {
            return true;
        }
        ExtractionResult payload = entity.getPayload();
        if (payload == null || payload.clientProfile() == null) {
            return false;
        }
        ClientProfile profile = payload.clientProfile();
        return containsIgnoreCase(profile.clientName(), needle)
                || containsIgnoreCase(profile.phone(), needle)
                || containsIgnoreCase(profile.projectLocation(), needle);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ExtractionStatus parseStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter.trim())) {
            return null;
        }
        String normalized = statusFilter.trim().toUpperCase(Locale.ROOT);
        if ("NEEDS_REVIEW".equals(normalized)) {
            return ExtractionStatus.NEEDS_REVIEW;
        }
        if ("APPROVED".equals(normalized)) {
            return ExtractionStatus.APPROVED;
        }
        throw new IllegalArgumentException(
                "Invalid statusFilter '" + statusFilter + "'. Allowed values: ALL, NEEDS_REVIEW, APPROVED.");
    }

    private static BigDecimal resolveQuotedValue(BillOfMaterialsItem item, int qty) {
        if (item.lineTotal() != null) {
            return item.lineTotal();
        }
        if (item.unitPrice() != null) {
            return item.unitPrice().multiply(BigDecimal.valueOf(qty));
        }
        return BigDecimal.ZERO;
    }

    private static ClientProjectSummaryDto toClientProjectSummary(ExtractedDataEntity entity) {
        ExtractionResult payload = entity.getPayload();
        ContractEntity contract = entity.getContract();
        FinancialCommitments financials = payload != null ? payload.financialCommitments() : null;
        List<BillOfMaterialsItem> bom = (payload != null && payload.billOfMaterials() != null)
                ? payload.billOfMaterials()
                : List.of();
        List<String> warnings = (payload != null && payload.warnings() != null)
                ? payload.warnings()
                : List.of();

        return new ClientProjectSummaryDto(
                entity.getExtractionId(),
                contract != null ? contract.getSourceType() : null,
                contract != null ? contract.getFileName() : null,
                entity.getReviewStatus(),
                payload != null ? payload.clientProfile() : null,
                financials != null ? financials.totalAmount() : null,
                financials != null ? financials.currency() : null,
                entity.isMathVerified(),
                payload != null ? payload.contractTerms() : null,
                bom.size(),
                bom,
                warnings
        );
    }

    private static PendingInstallmentItemDto toPendingInstallmentItem(ExtractedDataEntity entity) {
        ExtractionResult payload = entity.getPayload();
        ClientProfile profile = payload != null ? payload.clientProfile() : null;
        FinancialCommitments financials = payload != null ? payload.financialCommitments() : null;

        return new PendingInstallmentItemDto(
                entity.getExtractionId(),
                profile != null ? profile.clientName() : null,
                profile != null ? profile.projectLocation() : null,
                entity.getReviewStatus(),
                financials != null ? financials.totalAmount() : null,
                financials != null ? financials.currency() : null,
                entity.isMathVerified(),
                (financials != null && financials.installments() != null) ? financials.installments() : List.of(),
                (payload != null && payload.warnings() != null) ? payload.warnings() : List.of()
        );
    }

    private record BomGroupKey(
            String normalizedDeviceKey,
            DeviceCategory category,
            CommunicationProtocol protocol,
            WiringRequirement wiringRequirement
    ) {}

    private static final class BomAccumulator {
        private final String displayName;
        private int totalQuantity = 0;
        private BigDecimal totalQuotedValue = BigDecimal.ZERO;
        private final Set<String> projectIds = new HashSet<>();

        private BomAccumulator(String displayName) {
            this.displayName = displayName;
        }
    }
}
