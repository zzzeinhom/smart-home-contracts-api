package com.zzzeinhom.contracts.ai.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SmartDocumentIngestionRouter {

    private static final int MIN_DIGITAL_PDF_TEXT_LENGTH = 80;
    private static final int MAX_IMAGE_WIDTH = 1600;
    private static final float PDF_RENDER_DPI = 150f;
    private static final float JPEG_QUALITY = 0.85f;

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md");
    private static final Set<String> EXCEL_EXTENSIONS = Set.of("xlsx", "xls");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> EXCEL_MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel"
    );

    private final int maxPdfPages;

    public SmartDocumentIngestionRouter(@Value("${app.ingestion.max-pdf-pages:10}") int maxPdfPages) {
        this.maxPdfPages = maxPdfPages > 0 ? maxPdfPages : 10;
    }

    public RoutedDocument route(DocumentPayload payload) {
        if (payload == null || payload.content() == null || payload.content().length == 0) {
            throw new IllegalArgumentException("Uploaded document content must not be empty.");
        }

        String safeName = (payload.fileName() != null && !payload.fileName().isBlank())
                ? payload.fileName().trim()
                : "contract";
        String mimeType = normalizeMimeType(payload.contentType());
        String extension = extractExtension(safeName);

        if (isText(mimeType, extension)) {
            return routeText(safeName, payload.content());
        }
        if (isExcel(mimeType, extension)) {
            return routeExcel(safeName, payload.content());
        }
        if (isImage(mimeType, extension)) {
            return routeImage(safeName, mimeType, extension, payload.content());
        }
        if (isPdf(mimeType, extension)) {
            return routePdf(safeName, payload.content(), payload.mode());
        }

        throw new UnsupportedDocumentTypeException(
                "Unsupported document type for '" + safeName + "' (contentType='" + payload.contentType()
                        + "'). Supported types: .txt, .md, .xlsx, .xls, .pdf, .jpg, .jpeg, .png, .webp.");
    }

    private RoutedDocument.TextDocument routeText(String fileName, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Uploaded text document '" + fileName + "' is empty.");
        }
        return new RoutedDocument.TextDocument(text, "[TEXT_INGESTED: " + fileName + "]");
    }

    private RoutedDocument.TextDocument routeExcel(String fileName, byte[] bytes) {
        StringBuilder markdown = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetMarkdown = convertSheetToMarkdown(sheet, formatter, evaluator);
                if (!sheetMarkdown.isEmpty()) {
                    if (!markdown.isEmpty()) {
                        markdown.append("\n\n");
                    }
                    markdown.append(sheetMarkdown);
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to parse Excel spreadsheet '" + fileName + "': " + ex.getMessage(), ex);
        }

        if (markdown.isEmpty()) {
            throw new IllegalArgumentException("Uploaded Excel spreadsheet '" + fileName + "' contains no non-empty rows.");
        }
        return new RoutedDocument.TextDocument(markdown.toString(), "[EXCEL_INGESTED: " + fileName + "]");
    }

    private static String convertSheetToMarkdown(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;

        for (Row row : sheet) {
            int lastCellNum = row.getLastCellNum();
            if (lastCellNum <= 0) {
                continue;
            }
            List<String> values = new ArrayList<>(lastCellNum);
            boolean hasValue = false;
            for (int col = 0; col < lastCellNum; col++) {
                Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String formatted = formatCell(cell, formatter, evaluator);
                if (!formatted.isEmpty()) {
                    hasValue = true;
                }
                values.add(formatted);
            }
            if (hasValue) {
                maxCols = Math.max(maxCols, values.size());
                rows.add(values);
            }
        }

        if (rows.isEmpty() || maxCols == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### Sheet: ").append(sheet.getSheetName()).append('\n');

        appendMarkdownRow(sb, rows.get(0), maxCols);
        sb.append('|');
        for (int col = 0; col < maxCols; col++) {
            sb.append(" --- |");
        }
        sb.append('\n');

        for (int r = 1; r < rows.size(); r++) {
            appendMarkdownRow(sb, rows.get(r), maxCols);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendMarkdownRow(StringBuilder sb, List<String> row, int colCount) {
        sb.append('|');
        for (int col = 0; col < colCount; col++) {
            String val = col < row.size() ? row.get(col) : "";
            sb.append(' ').append(val).append(" |");
        }
        sb.append('\n');
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
        return raw == null ? "" : raw.replace("|", "\\|").replaceAll("\\r?\\n+", " ").trim();
    }

    private RoutedDocument.VisionDocument routeImage(String fileName, String mimeType, String extension, byte[] bytes) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read image '" + fileName + "': " + ex.getMessage(), ex);
        }

        if (source == null) {
            if ("webp".equals(extension) || "image/webp".equals(mimeType)) {
                Media webpMedia = new Media(MimeType.valueOf("image/webp"), new ByteArrayResource(bytes));
                return new RoutedDocument.VisionDocument(List.of(webpMedia), "[IMAGE_VISION_INGESTED: " + fileName + "]");
            }
            throw new IllegalArgumentException("Unable to decode image file '" + fileName + "'.");
        }

        try {
            byte[] jpegBytes = normalizeAndEncodeJpeg(source);
            Media media = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(jpegBytes));
            return new RoutedDocument.VisionDocument(List.of(media), "[IMAGE_VISION_INGESTED: " + fileName + "]");
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to encode image '" + fileName + "' as JPEG: " + ex.getMessage(), ex);
        } finally {
            source.flush();
        }
    }

    private RoutedDocument routePdf(String fileName, byte[] bytes, ExtractionMode mode) {
        try (PDDocument pdDocument = Loader.loadPDF(bytes)) {
            int pageCount = pdDocument.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Uploaded PDF '" + fileName + "' contains no pages.");
            }
            if (pageCount > maxPdfPages) {
                throw new IllegalArgumentException(
                        "PDF page count (" + pageCount + ") exceeds maximum allowed limit (" + maxPdfPages + ").");
            }

            if (mode == ExtractionMode.AUTO) {
                String extractedText = new PDFTextStripper().getText(pdDocument);
                String normalized = extractedText == null ? "" : extractedText.replaceAll("\\s+", " ").trim();
                if (normalized.length() >= MIN_DIGITAL_PDF_TEXT_LENGTH) {
                    return new RoutedDocument.TextDocument(
                            extractedText.trim(),
                            "[PDF_TEXT_INGESTED: " + fileName + ", pages=" + pageCount + "]"
                    );
                }
            }

            PDFRenderer renderer = new PDFRenderer(pdDocument);
            List<Media> pageImages = new ArrayList<>(pageCount);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI, ImageType.RGB);
                try {
                    byte[] jpegBytes = normalizeAndEncodeJpeg(pageImage);
                    pageImages.add(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(jpegBytes)));
                } finally {
                    pageImage.flush();
                }
            }

            return new RoutedDocument.VisionDocument(
                    List.copyOf(pageImages),
                    "[PDF_VISION_INGESTED: " + fileName + ", pages=" + pageCount + "]"
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to parse PDF document '" + fileName + "': " + ex.getMessage(), ex);
        }
    }

    private static byte[] normalizeAndEncodeJpeg(BufferedImage source) throws IOException {
        boolean needsResize = source.getWidth() > MAX_IMAGE_WIDTH;
        boolean needsColorConversion = source.getType() != BufferedImage.TYPE_INT_RGB;

        if (!needsResize && !needsColorConversion) {
            return writeJpeg(source);
        }

        int targetWidth = needsResize ? MAX_IMAGE_WIDTH : source.getWidth();
        int targetHeight = needsResize
                ? Math.max(1, (int) Math.round((double) source.getHeight() * MAX_IMAGE_WIDTH / source.getWidth()))
                : source.getHeight();

        BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        try {
            Graphics2D g2d = normalized.createGraphics();
            try {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, targetWidth, targetHeight);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g2d.dispose();
            }
            return writeJpeg(normalized);
        } finally {
            normalized.flush();
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter available in JVM.");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static String normalizeMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int semicolonIndex = contentType.indexOf(';');
        String base = (semicolonIndex >= 0) ? contentType.substring(0, semicolonIndex) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String extractExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isText(String mimeType, String extension) {
        return TEXT_EXTENSIONS.contains(extension) || mimeType.startsWith("text/");
    }

    private static boolean isExcel(String mimeType, String extension) {
        return EXCEL_EXTENSIONS.contains(extension) || EXCEL_MIME_TYPES.contains(mimeType);
    }

    private static boolean isImage(String mimeType, String extension) {
        return IMAGE_EXTENSIONS.contains(extension) || mimeType.startsWith("image/");
    }

    private static boolean isPdf(String mimeType, String extension) {
        return "pdf".equals(extension) || "application/pdf".equals(mimeType);
    }
}
