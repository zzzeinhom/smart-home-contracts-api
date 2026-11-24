package com.zzzeinhom.contracts.ai.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogEmbeddingServiceTest {

    @Test
    void embed_truncatesAndL2NormalizesOversizedMatryoshkaVector() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        float[] oversizedVector = new float[4096];
        for (int i = 0; i < oversizedVector.length; i++) {
            oversizedVector[i] = (i % 5) + 1.0f;
        }
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(oversizedVector, 0))));

        CatalogEmbeddingService service = new CatalogEmbeddingService(embeddingModel, "embedding-model", 1024);
        float[] result = service.embed("Sonoff Mini R4M Zigbee Smart Switch");

        assertEquals(1024, result.length);
        double sumSquares = 0.0;
        for (float v : result) {
            sumSquares += (double) v * v;
        }
        assertEquals(1.0, Math.sqrt(sumSquares), 1e-5);

        String pgLiteral = CatalogEmbeddingService.toPgVectorLiteral(result);
        assertTrue(pgLiteral.startsWith("["));
        assertTrue(pgLiteral.endsWith("]"));
        assertEquals(1024, pgLiteral.substring(1, pgLiteral.length() - 1).split(",").length);
    }

    @Test
    void embed_throwsWhenReturnedVectorSmallerThanTargetDimensions() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        float[] undersized = new float[512];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(undersized, 0))));

        CatalogEmbeddingService service = new CatalogEmbeddingService(embeddingModel, "embedding-model", 1024);

        assertThrows(IllegalStateException.class, () -> service.embed("Aqara Hub M3"));
    }
}
