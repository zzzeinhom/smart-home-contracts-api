package com.zzzeinhom.contracts.ai.ingestion;

public record DocumentPayload(
        String fileName,
        String contentType,
        byte[] content,
        ExtractionMode mode
) {
    public DocumentPayload {
        mode = (mode == null) ? ExtractionMode.AUTO : mode;
    }
}
