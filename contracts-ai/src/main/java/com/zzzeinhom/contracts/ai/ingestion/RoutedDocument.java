package com.zzzeinhom.contracts.ai.ingestion;

import org.springframework.ai.content.Media;

import java.util.List;

public sealed interface RoutedDocument permits RoutedDocument.TextDocument, RoutedDocument.VisionDocument {

    String sourceSummary();

    record TextDocument(String extractedText, String sourceSummary) implements RoutedDocument {
    }

    record VisionDocument(List<Media> images, String sourceSummary) implements RoutedDocument {
    }
}
