package com.zzzeinhom.contracts.ai.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class CatalogEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final String embeddingModelName;
    private final int targetDimensions;

    public CatalogEmbeddingService(
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.options.model:embedding-model}") String embeddingModelName,
            @Value("${spring.ai.openai.embedding.options.dimensions:1024}") int targetDimensions) {
        if (targetDimensions <= 0) {
            throw new IllegalArgumentException("targetDimensions must be positive.");
        }
        this.embeddingModel = embeddingModel;
        this.embeddingModelName = embeddingModelName;
        this.targetDimensions = targetDimensions;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding input text must not be blank.");
        }

        EmbeddingRequest request = new EmbeddingRequest(
                List.of(text),
                OpenAiEmbeddingOptions.builder()
                        .model(embeddingModelName)
                        .dimensions(targetDimensions)
                        .build()
        );

        EmbeddingResponse response = embeddingModel.call(request);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Embedding provider returned an empty embedding response.");
        }

        float[] rawVector = response.getResult().getOutput();
        if (rawVector.length < targetDimensions) {
            throw new IllegalStateException(
                    "Embedding vector length (" + rawVector.length + ") is smaller than target dimensions ("
                            + targetDimensions + ").");
        }

        if (rawVector.length == targetDimensions) {
            return rawVector;
        }

        // Matryoshka Safety Guard: truncate to targetDimensions and re-normalize to unit L2 norm
        float[] sliced = new float[targetDimensions];
        double sumSquares = 0.0;
        for (int i = 0; i < targetDimensions; i++) {
            float val = rawVector[i];
            sliced[i] = val;
            sumSquares += (double) val * val;
        }

        double norm = Math.sqrt(sumSquares);
        if (norm > 0.0) {
            for (int i = 0; i < targetDimensions; i++) {
                sliced[i] = (float) (sliced[i] / norm);
            }
        }
        return sliced;
    }

    public static String toPgVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Vector must not be null or empty.");
        }
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
