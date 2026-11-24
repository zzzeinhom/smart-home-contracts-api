package com.zzzeinhom.contracts.ai.ingestion;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartDocumentIngestionRouterTest {

    private final SmartDocumentIngestionRouter router = new SmartDocumentIngestionRouter(10);

    @Test
    void route_excelWorkbook_returnsMarkdownTextDocumentPreservingArabicAndEnglish() throws IOException {
        byte[] xlsxBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Quotation");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item / البند");
            header.createCell(1).setCellValue("Qty");
            header.createCell(2).setCellValue("Unit Price");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("مفتاح سمارت Sonoff Mini R4M");
            row1.createCell(1).setCellValue(10);
            row1.createCell(2).setCellValue(850.50);

            workbook.write(baos);
            xlsxBytes = baos.toByteArray();
        }

        RoutedDocument routed = router.route(new DocumentPayload(
                "villa-quote.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes,
                ExtractionMode.AUTO
        ));

        RoutedDocument.TextDocument textDoc = assertInstanceOf(RoutedDocument.TextDocument.class, routed);
        assertTrue(textDoc.extractedText().contains("### Sheet: Quotation"));
        assertTrue(textDoc.extractedText().contains("| Item / البند | Qty | Unit Price |"));
        assertTrue(textDoc.extractedText().contains("| --- | --- | --- |"));
        assertTrue(textDoc.extractedText().contains("| مفتاح سمارت Sonoff Mini R4M | 10 | 850.5 |"));
    }

    @Test
    void route_pngImage_returnsVisionDocumentWithResizedJpegMedia() throws IOException {
        byte[] pngBytes;
        BufferedImage img = new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_ARGB);
        try {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, 2400, 1200);
            g.dispose();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(img, "png", baos);
                pngBytes = baos.toByteArray();
            }
        } finally {
            img.flush();
        }

        RoutedDocument routed = router.route(new DocumentPayload(
                "contract-page1.png",
                "image/png",
                pngBytes,
                ExtractionMode.AUTO
        ));

        RoutedDocument.VisionDocument visionDoc = assertInstanceOf(RoutedDocument.VisionDocument.class, routed);
        assertEquals(1, visionDoc.images().size());
        assertEquals(MimeTypeUtils.IMAGE_JPEG, visionDoc.images().get(0).getMimeType());

        byte[] jpegBytes = visionDoc.images().get(0).getDataAsByteArray();
        assertTrue(jpegBytes.length > 2);
        assertEquals((byte) 0xFF, jpegBytes[0]);
        assertEquals((byte) 0xD8, jpegBytes[1]);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        try {
            assertEquals(1600, decoded.getWidth());
            assertEquals(800, decoded.getHeight());
        } finally {
            if (decoded != null) {
                decoded.flush();
            }
        }
    }

    @Test
    void route_unsupportedExecutable_throwsUnsupportedDocumentTypeException() {
        DocumentPayload payload = new DocumentPayload(
                "malware.exe",
                "application/octet-stream",
                new byte[]{0x4D, 0x5A, 0x00, 0x01},
                ExtractionMode.AUTO
        );

        assertThrows(UnsupportedDocumentTypeException.class, () -> router.route(payload));
    }
}
