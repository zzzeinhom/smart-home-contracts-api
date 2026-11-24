package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.dto.CatalogItemRequest;
import com.zzzeinhom.contracts.dto.CatalogItemResponse;
import com.zzzeinhom.contracts.dto.CatalogSearchMatchResponse;
import com.zzzeinhom.contracts.service.CatalogManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogManagementService catalogManagementService;

    public CatalogController(CatalogManagementService catalogManagementService) {
        this.catalogManagementService = catalogManagementService;
    }

    @PostMapping("/items")
    public ResponseEntity<List<CatalogItemResponse>> addCatalogItems(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody List<@Valid CatalogItemRequest> requests) {
        List<CatalogItemResponse> created = catalogManagementService.addCatalogItems(
                resolveTenantId(tenantId), requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/items/import")
    public ResponseEntity<List<CatalogItemResponse>> importCatalogFromExcel(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded Excel catalog file must not be empty.");
        }
        List<CatalogItemResponse> imported = catalogManagementService.importFromExcel(
                resolveTenantId(tenantId), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(imported);
    }

    @GetMapping("/items")
    public ResponseEntity<List<CatalogItemResponse>> listCatalogItems(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ResponseEntity.ok(catalogManagementService.listItems(resolveTenantId(tenantId)));
    }

    @GetMapping("/search")
    public ResponseEntity<List<CatalogSearchMatchResponse>> searchCatalog(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return ResponseEntity.ok(
                catalogManagementService.semanticSearch(resolveTenantId(tenantId), query, limit));
    }

    @PostMapping("/items/reindex")
    public ResponseEntity<Map<String, Integer>> reindexMissingEmbeddings(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        int count = catalogManagementService.reindexMissingEmbeddings(resolveTenantId(tenantId));
        return ResponseEntity.ok(Map.of("reindexedCount", count));
    }

    private static String resolveTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim();
    }
}
