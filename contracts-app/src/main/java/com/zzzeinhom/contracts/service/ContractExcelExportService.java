package com.zzzeinhom.contracts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.model.FinancialCommitments;
import com.zzzeinhom.contracts.domain.model.Installment;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class ContractExcelExportService {

    static final String SHEET_NAME = "Quotation & BoM";
    private static final int BOM_COLUMN_COUNT = 10;
    private static final int MIN_COLUMN_WIDTH = 10 * 256;
    private static final int MAX_COLUMN_WIDTH = 48 * 256;

    private final ExtractedDataRepository extractedDataRepository;
    private final ObjectMapper objectMapper;

    public ContractExcelExportService(
            ExtractedDataRepository extractedDataRepository,
            ObjectMapper objectMapper) {
        this.extractedDataRepository = extractedDataRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public byte[] exportToExcel(String tenantId, String extractionId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank.");
        }
        if (extractionId == null || extractionId.isBlank()) {
            throw new IllegalArgumentException("extractionId must not be blank.");
        }

        String normalizedTenant = tenantId.trim().toLowerCase(Locale.ROOT);
        String trimmedExtractionId = extractionId.trim();

        ExtractedDataEntity entity = extractedDataRepository
                .findByExtractionIdAndTenantId(trimmedExtractionId, normalizedTenant)
                .orElseThrow(() -> new NoSuchElementException("Extraction not found: " + trimmedExtractionId));

        ExtractionResult result = resolvePayload(entity);
        String currency = resolveCurrency(result.financialCommitments());

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            WorkbookStyles styles = WorkbookStyles.create(workbook);

            int rowIdx = 0;
            rowIdx = writeTitleAndMetadata(sheet, styles, entity, result, rowIdx);
            rowIdx++;
            rowIdx = writeBomTable(sheet, styles, result.billOfMaterials(), currency, rowIdx);
            rowIdx++;
            rowIdx = writeFinancialSummary(sheet, styles, result.financialCommitments(), currency, rowIdx);
            rowIdx++;
            rowIdx = writeInstallmentsTable(sheet, styles, result.financialCommitments(), currency, rowIdx);
            rowIdx++;
            writeTermsAndWarnings(sheet, styles, result.contractTerms(), result.warnings(), rowIdx);

            autoSizeColumnsSafely(sheet, BOM_COLUMN_COUNT);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate Excel export for extraction: " + trimmedExtractionId, ex);
        }
    }

    private ExtractionResult resolvePayload(ExtractedDataEntity entity) {
        ExtractionResult payload = entity.getPayload();
        if (payload == null) {
            throw new IllegalStateException("Extraction payload is empty for: " + entity.getExtractionId());
        }
        return objectMapper.convertValue(payload, ExtractionResult.class);
    }

    private static int writeTitleAndMetadata(
            Sheet sheet,
            WorkbookStyles styles,
            ExtractedDataEntity entity,
            ExtractionResult result,
            int startRow) {
        int rowIdx = startRow;

        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(26);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Smart Home Contract & Quotation BoM");
        titleCell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(titleRow.getRowNum(), titleRow.getRowNum(), 0, BOM_COLUMN_COUNT - 1));

        String statusLabel = entity.getReviewStatus() == ExtractionStatus.APPROVED
                ? "APPROVED"
                : "DRAFT - NEEDS REVIEW";
        ClientProfile profile = result.clientProfile();

        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Extraction ID", entity.getExtractionId());
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Status", statusLabel);
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Client Name", profile != null ? defaultText(profile.clientName()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Phone", profile != null ? defaultText(profile.phone()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Project Location", profile != null ? defaultText(profile.projectLocation()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Contract Date",
                profile != null && profile.contractDate() != null ? profile.contractDate().toString() : "-");
        return rowIdx;
    }

    private static int writeBomTable(
            Sheet sheet,
            WorkbookStyles styles,
            List<BillOfMaterialsItem> items,
            String currency,
            int startRow) {
        int rowIdx = writeSectionHeading(sheet, styles, startRow, "Bill of Materials");

        Row headerRow = sheet.createRow(rowIdx++);
        String[] headers = {
                "#",
                "Category",
                "Raw Description",
                "Normalized Catalog Device",
                "Protocol",
                "Wiring",
                "Qty",
                "Unit Price (" + currency + ")",
                "Line Total (" + currency + ")",
                "Notes"
        };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.tableHeader);
        }

        if (items == null || items.isEmpty()) {
            Row emptyRow = sheet.createRow(rowIdx++);
            Cell cell = emptyRow.createCell(0);
            cell.setCellValue("No Bill of Materials items present.");
            cell.setCellStyle(styles.body);
            return rowIdx;
        }

        int lineNo = 1;
        for (BillOfMaterialsItem item : items) {
            if (item == null) {
                continue;
            }
            Row row = sheet.createRow(rowIdx++);
            createTextCell(row, 0, String.valueOf(lineNo++), styles.bodyCenter);
            createTextCell(row, 1, item.category() != null ? item.category().name() : "-", styles.body);
            createTextCell(row, 2, defaultText(item.rawName()), styles.body);
            createTextCell(row, 3, defaultText(item.normalizedDevice()), styles.body);
            createTextCell(row, 4, item.protocol() != null ? item.protocol().name() : "-", styles.body);
            createTextCell(row, 5, item.wiringRequirement() != null ? item.wiringRequirement().name() : "-", styles.body);

            Cell qtyCell = row.createCell(6);
            qtyCell.setCellValue(item.quantity());
            qtyCell.setCellStyle(styles.integer);

            writeMoneyOrTbdCell(row, 7, item.unitPrice(), styles);
            writeMoneyOrTbdCell(row, 8, item.lineTotal(), styles);
            createTextCell(row, 9, defaultText(item.notes()), styles.body);
        }
        return rowIdx;
    }

    private static int writeFinancialSummary(
            Sheet sheet,
            WorkbookStyles styles,
            FinancialCommitments financials,
            String currency,
            int startRow) {
        int rowIdx = writeSectionHeading(sheet, styles, startRow, "Financial Summary");

        rowIdx = writeMoneyRow(sheet, styles, rowIdx, "Subtotal", financials != null ? financials.subtotal() : null);
        rowIdx = writeMoneyRow(sheet, styles, rowIdx, "Discount", financials != null ? financials.discountAmount() : null);
        rowIdx = writeMoneyRow(sheet, styles, rowIdx, "VAT (14%)", financials != null ? financials.vatAmount() : null);
        rowIdx = writeMoneyRow(sheet, styles, rowIdx, "Grand Total", financials != null ? financials.totalAmount() : null);
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Currency", currency);
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Math Verified",
                financials != null && financials.isMathVerified() ? "YES" : "NO");
        return rowIdx;
    }

    private static int writeInstallmentsTable(
            Sheet sheet,
            WorkbookStyles styles,
            FinancialCommitments financials,
            String currency,
            int startRow) {
        int rowIdx = writeSectionHeading(sheet, styles, startRow, "Installment Schedule");

        Row header = sheet.createRow(rowIdx++);
        createTextCell(header, 0, "Stage", styles.tableHeader);
        createTextCell(header, 1, "Amount (" + currency + ")", styles.tableHeader);
        createTextCell(header, 2, "Due Condition", styles.tableHeader);

        List<Installment> installments = financials != null ? financials.installments() : null;
        if (installments == null || installments.isEmpty()) {
            Row emptyRow = sheet.createRow(rowIdx++);
            createTextCell(emptyRow, 0, "No installments defined.", styles.body);
            return rowIdx;
        }

        for (Installment installment : installments) {
            if (installment == null) {
                continue;
            }
            Row row = sheet.createRow(rowIdx++);
            createTextCell(row, 0, defaultText(installment.stage()), styles.body);
            writeMoneyOrTbdCell(row, 1, installment.amount(), styles);
            createTextCell(row, 2, defaultText(installment.dueCondition()), styles.body);
        }
        return rowIdx;
    }

    private static int writeTermsAndWarnings(
            Sheet sheet,
            WorkbookStyles styles,
            ContractTerms terms,
            List<String> warnings,
            int startRow) {
        int rowIdx = writeSectionHeading(sheet, styles, startRow, "Commercial Terms & Engineering Warnings");

        String fxLinked = (terms != null && terms.priceLinkedToExchangeRate() != null)
                ? (terms.priceLinkedToExchangeRate() ? "YES" : "NO")
                : "Unspecified";
        String exclusions = (terms != null && terms.exclusions() != null && !terms.exclusions().isEmpty())
                ? String.join("; ", terms.exclusions())
                : "None specified";

        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Scope Type", terms != null ? defaultText(terms.scopeType()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Warranty", terms != null ? defaultText(terms.warrantyPeriod()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Quote Validity", terms != null ? defaultText(terms.quoteValidity()) : "-");
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "FX Linked", fxLinked);
        rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Exclusions", exclusions);

        if (warnings == null || warnings.isEmpty()) {
            rowIdx = writeKeyValueRow(sheet, styles, rowIdx, "Warnings", "None");
        } else {
            for (int i = 0; i < warnings.size(); i++) {
                String label = i == 0 ? "Warnings" : "";
                rowIdx = writeKeyValueRow(sheet, styles, rowIdx, label, defaultText(warnings.get(i)));
            }
        }
        return rowIdx;
    }

    private static int writeSectionHeading(Sheet sheet, WorkbookStyles styles, int rowIdx, String heading) {
        Row row = sheet.createRow(rowIdx);
        Cell cell = row.createCell(0);
        cell.setCellValue(heading);
        cell.setCellStyle(styles.sectionHeader);
        return rowIdx + 1;
    }

    private static int writeKeyValueRow(Sheet sheet, WorkbookStyles styles, int rowIdx, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        createTextCell(row, 0, label, styles.label);
        createTextCell(row, 1, value, styles.body);
        return rowIdx + 1;
    }

    private static int writeMoneyRow(Sheet sheet, WorkbookStyles styles, int rowIdx, String label, BigDecimal amount) {
        Row row = sheet.createRow(rowIdx);
        createTextCell(row, 0, label, styles.label);
        writeMoneyOrTbdCell(row, 1, amount, styles);
        return rowIdx + 1;
    }

    private static void createTextCell(Row row, int colIdx, String text, CellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static void writeMoneyOrTbdCell(Row row, int colIdx, BigDecimal amount, WorkbookStyles styles) {
        Cell cell = row.createCell(colIdx);
        if (amount == null) {
            cell.setCellValue("TBD");
            cell.setCellStyle(styles.bodyCenter);
        } else {
            cell.setCellValue(amount.doubleValue());
            cell.setCellStyle(styles.currency);
        }
    }

    private static void autoSizeColumnsSafely(Sheet sheet, int columnCount) {
        for (int col = 0; col < columnCount; col++) {
            int width;
            try {
                sheet.autoSizeColumn(col);
                width = sheet.getColumnWidth(col) + 512;
            } catch (Throwable ex) {
                width = computeFallbackWidth(sheet, col);
            }
            sheet.setColumnWidth(col, Math.max(MIN_COLUMN_WIDTH, Math.min(width, MAX_COLUMN_WIDTH)));
        }
    }

    private static int computeFallbackWidth(Sheet sheet, int col) {
        int maxLen = 10;
        for (Row row : sheet) {
            Cell cell = row.getCell(col);
            if (cell == null) {
                continue;
            }
            int len = cell.toString().length();
            if (len > maxLen) {
                maxLen = len;
            }
        }
        return (maxLen + 4) * 256;
    }

    private static String resolveCurrency(FinancialCommitments financials) {
        if (financials == null || financials.currency() == null || financials.currency().isBlank()) {
            return "EGP";
        }
        return financials.currency().trim();
    }

    private static String defaultText(String value) {
        return (value == null || value.isBlank()) ? "-" : value.trim();
    }

    private record WorkbookStyles(
            CellStyle title,
            CellStyle sectionHeader,
            CellStyle tableHeader,
            CellStyle label,
            CellStyle body,
            CellStyle bodyCenter,
            CellStyle integer,
            CellStyle currency) {

        private static WorkbookStyles create(XSSFWorkbook workbook) {
            DataFormat dataFormat = workbook.createDataFormat();

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 11);

            CellStyle sectionHeader = workbook.createCellStyle();
            sectionHeader.setFont(sectionFont);
            sectionHeader.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle tableHeader = workbook.createCellStyle();
            tableHeader.setFont(headerFont);
            tableHeader.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            tableHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            tableHeader.setAlignment(HorizontalAlignment.CENTER);
            applyThinBorders(tableHeader);

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            CellStyle label = workbook.createCellStyle();
            label.setFont(boldFont);
            applyThinBorders(label);

            CellStyle body = workbook.createCellStyle();
            body.setWrapText(true);
            applyThinBorders(body);

            CellStyle bodyCenter = workbook.createCellStyle();
            bodyCenter.setAlignment(HorizontalAlignment.CENTER);
            applyThinBorders(bodyCenter);

            CellStyle integer = workbook.createCellStyle();
            integer.setAlignment(HorizontalAlignment.RIGHT);
            integer.setDataFormat(dataFormat.getFormat("#,##0"));
            applyThinBorders(integer);

            CellStyle currency = workbook.createCellStyle();
            currency.setAlignment(HorizontalAlignment.RIGHT);
            currency.setDataFormat(dataFormat.getFormat("#,##0.00"));
            applyThinBorders(currency);

            return new WorkbookStyles(title, sectionHeader, tableHeader, label, body, bodyCenter, integer, currency);
        }

        private static void applyThinBorders(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
