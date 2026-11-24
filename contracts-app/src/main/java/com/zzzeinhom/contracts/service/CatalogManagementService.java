package com.zzzeinhom.contracts.service;

import com.zzzeinhom.contracts.ai.embedding.CatalogEmbeddingService;
import com.zzzeinhom.contracts.domain.enums.CommunicationProtocol;
import com.zzzeinhom.contracts.domain.enums.DeviceCategory;
import com.zzzeinhom.contracts.domain.enums.WiringRequirement;
import com.zzzeinhom.contracts.dto.CatalogItemRequest;
import com.zzzeinhom.contracts.dto.CatalogItemResponse;
import com.zzzeinhom.contracts.dto.CatalogSearchMatchResponse;
import com.zzzeinhom.contracts.persistence.entity.DeviceCatalogEntity;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogRepository;
import com.zzzeinhom.contracts.persistence.repository.DeviceCatalogVectorRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogManagementService {

    private static final Logger log = LoggerFactory.getLogger(CatalogManagementService.class);

    private final DeviceCatalogRepository catalogRepository;
    private final DeviceCatalogVectorRepository vectorRepository;
    private final CatalogEmbeddingService embeddingService;
    private final TransactionTemplate transactionTemplate;

    public CatalogManagementService(
            DeviceCatalogRepository catalogRepository,
            DeviceCatalogVectorRepository vectorRepository,
            CatalogEmbeddingService embeddingService,
            TransactionTemplate transactionTemplate) {
        this.catalogRepository = catalogRepository;
        this.vectorRepository = vectorRepository;
        this.embeddingService = embeddingService;
        this.transactionTemplate = transactionTemplate;
    }

    public List<CatalogItemResponse> addCatalogItems(String tenantId, List<CatalogItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Catalog items list must not be empty.");
        }

        List<PreparedCatalogItem> prepared = new ArrayList<>(requests.size());
        for (CatalogItemRequest req : requests) {
            String canonicalText = buildCanonicalText(req);
            String pgVectorLiteral = embedOrThrow(req.normalizedName(), canonicalText);
            prepared.add(new PreparedCatalogItem(req, pgVectorLiteral));
        }

        return transactionTemplate.execute(status -> {
            List<CatalogItemResponse> responses = new ArrayList<>(prepared.size());
            for (PreparedCatalogItem item : prepared) {
                CatalogItemRequest req = item.request();
                String normalizedName = req.normalizedName().trim();
                DeviceCatalogEntity entity = catalogRepository
                        .findByTenantIdAndNormalizedName(tenantId, normalizedName)
                        .map(existing -> {
                            existing.updateDetails(
                                    req.category(),
                                    trimToNull(req.brand()),
                                    trimToNull(req.modelCode()),
                                    req.protocol(),
                                    req.wiringRequirement(),
                                    req.referenceUnitPrice(),
                                    trimToNull(req.currency()),
                                    trimToNull(req.description())
                            );
                            return existing;
                        })
                        .orElseGet(() -> new DeviceCatalogEntity(
                                tenantId,
                                req.category(),
                                normalizedName,
                                trimToNull(req.brand()),
                                trimToNull(req.modelCode()),
                                req.protocol(),
                                req.wiringRequirement(),
                                req.referenceUnitPrice(),
                                trimToNull(req.currency()),
                                trimToNull(req.description())
                        ));
                DeviceCatalogEntity saved = catalogRepository.saveAndFlush(entity);
                vectorRepository.updateEmbedding(saved.getId(), tenantId, item.pgVectorLiteral());
                responses.add(CatalogItemResponse.from(saved));
            }
            return responses;
        });
    }

    public List<CatalogItemResponse> importFromExcel(String tenantId, byte[] excelBytes) {
        if (excelBytes == null || excelBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded Excel file must not be empty.");
        }

        List<CatalogItemRequest> parsedRequests = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                parseSheetIntoRequests(sheet, formatter, evaluator, parsedRequests);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to parse catalog Excel workbook: " + ex.getMessage(), ex);
        }

        if (parsedRequests.isEmpty()) {
            throw new IllegalArgumentException("Catalog Excel workbook contains no importable device rows.");
        }
        return addCatalogItems(tenantId, parsedRequests);
    }

    public List<CatalogItemResponse> listItems(String tenantId) {
        return catalogRepository.findAllByTenantIdOrderByCategoryAscNormalizedNameAsc(tenantId).stream()
                .map(CatalogItemResponse::from)
                .toList();
    }

    public List<CatalogSearchMatchResponse> semanticSearch(String tenantId, String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query parameter 'q' must not be blank.");
        }
        String pgVectorLiteral = embedOrThrow("semantic-search", query.trim());
        return vectorRepository.findTopMatches(tenantId, pgVectorLiteral, limit).stream()
                .map(CatalogSearchMatchResponse::from)
                .toList();
    }

    public int reindexMissingEmbeddings(String tenantId) {
        List<UUID> missingIds = vectorRepository.findIdsWithMissingEmbedding(tenantId);
        if (missingIds.isEmpty()) {
            return 0;
        }

        List<DeviceCatalogEntity> entities = catalogRepository.findAllById(missingIds).stream()
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .toList();
        if (entities.isEmpty()) {
            return 0;
        }

        record PreparedReindex(UUID id, String pgVectorLiteral) {}
        List<PreparedReindex> prepared = new ArrayList<>(entities.size());
        for (DeviceCatalogEntity entity : entities) {
            String pgVectorLiteral = embedOrThrow(entity.getNormalizedName(), buildCanonicalText(entity));
            prepared.add(new PreparedReindex(entity.getId(), pgVectorLiteral));
        }

        transactionTemplate.executeWithoutResult(status -> {
            for (PreparedReindex item : prepared) {
                vectorRepository.updateEmbedding(item.id(), tenantId, item.pgVectorLiteral());
            }
        });
        return prepared.size();
    }

    private String embedOrThrow(String itemLabel, String text) {
        try {
            float[] vector = embeddingService.embed(text);
            return CatalogEmbeddingService.toPgVectorLiteral(vector);
        } catch (Exception ex) {
            log.error("Failed to generate embedding for catalog item '{}'", itemLabel, ex);
            throw new IllegalStateException(
                    "Failed to generate embedding for catalog item '" + itemLabel + "': " + ex.getMessage(), ex);
        }
    }

    private static void parseSheetIntoRequests(
            Sheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            List<CatalogItemRequest> output) {
        Map<String, Integer> headers = null;

        for (Row row : sheet) {
            if (isBlankRow(row, formatter, evaluator)) {
                continue;
            }
            if (headers == null) {
                headers = readHeaderColumns(row, formatter, evaluator);
                continue;
            }

            String normalizedName = getColumnValue(row, headers, formatter, evaluator,
                    "normalized_name", "name", "device_name", "device");
            if (normalizedName == null || normalizedName.isBlank()) {
                continue;
            }

            String categoryRaw = getColumnValue(row, headers, formatter, evaluator, "category");
            String protocolRaw = getColumnValue(row, headers, formatter, evaluator, "protocol");
            String wiringRaw = getColumnValue(row, headers, formatter, evaluator, "wiring_requirement", "wiring");
            String priceRaw = getColumnValue(row, headers, formatter, evaluator,
                    "unit_price", "price", "reference_unit_price");

            DeviceCategory category = parseEnum(DeviceCategory.class, categoryRaw, "category", row.getRowNum() + 1);
            CommunicationProtocol protocol = parseEnum(
                    CommunicationProtocol.class, protocolRaw, "protocol", row.getRowNum() + 1);
            WiringRequirement wiring = (wiringRaw == null || wiringRaw.isBlank())
                    ? WiringRequirement.UNSPECIFIED
                    : parseEnum(WiringRequirement.class, wiringRaw, "wiring_requirement", row.getRowNum() + 1);
            BigDecimal referencePrice = parsePrice(priceRaw, row.getRowNum() + 1);

            output.add(new CatalogItemRequest(
                    category,
                    normalizedName.trim(),
                    trimToNull(getColumnValue(row, headers, formatter, evaluator, "brand")),
                    trimToNull(getColumnValue(row, headers, formatter, evaluator, "model_code", "sku", "model")),
                    protocol,
                    wiring,
                    referencePrice,
                    trimToNull(getColumnValue(row, headers, formatter, evaluator, "currency")),
                    trimToNull(getColumnValue(row, headers, formatter, evaluator, "description", "notes"))
            ));
        }
    }

    private static Map<String, Integer> readHeaderColumns(
            Row headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> map = new HashMap<>();
        for (int col = 0; col < headerRow.getLastCellNum(); col++) {
            String header = formatCell(headerRow.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                    formatter, evaluator);
            if (!header.isEmpty()) {
                String normalizedHeader = header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
                map.putIfAbsent(normalizedHeader, col);
            }
        }
        return map;
    }

    private static String getColumnValue(
            Row row,
            Map<String, Integer> headers,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases) {
        for (String alias : aliases) {
            Integer idx = headers.get(alias);
            if (idx != null) {
                return formatCell(row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter, evaluator);
            }
        }
        return null;
    }

    private static boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || row.getLastCellNum() <= 0) {
            return true;
        }
        for (int col = 0; col < row.getLastCellNum(); col++) {
            String value = formatCell(row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                    formatter, evaluator);
            if (!value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String formatCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        String raw;
        try {
            raw = formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ex) {
            raw = formatter.formatCellValue(cell);
        }
        return raw == null ? "" : raw.trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String field, int rowNum) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required column '" + field + "' at Excel row " + rowNum + ".");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid " + field + " value '" + raw + "' at Excel row " + rowNum + ".");
        }
    }

    private static BigDecimal parsePrice(String raw, int rowNum) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String sanitized = raw.replace(",", "").trim();
        try {
            return new BigDecimal(sanitized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Invalid unit price '" + raw + "' at Excel row " + rowNum + ".");
        }
    }

    private static String buildCanonicalText(CatalogItemRequest req) {
        return buildCanonicalText(
                req.category(),
                req.normalizedName(),
                req.brand(),
                req.modelCode(),
                req.protocol(),
                req.description()
        );
    }

    private static String buildCanonicalText(DeviceCatalogEntity entity) {
        return buildCanonicalText(
                entity.getCategory(),
                entity.getNormalizedName(),
                entity.getBrand(),
                entity.getModelCode(),
                entity.getProtocol(),
                entity.getDescription()
        );
    }

    private static String buildCanonicalText(
            DeviceCategory category,
            String normalizedName,
            String brand,
            String modelCode,
            CommunicationProtocol protocol,
            String description) {
        return nonNull(normalizedName)
                + " | Brand: " + nonNull(brand)
                + " | Model: " + nonNull(modelCode)
                + " | Category: " + category
                + " | Protocol: " + protocol
                + " | " + nonNull(description);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record PreparedCatalogItem(CatalogItemRequest request, String pgVectorLiteral) {
    }
}
