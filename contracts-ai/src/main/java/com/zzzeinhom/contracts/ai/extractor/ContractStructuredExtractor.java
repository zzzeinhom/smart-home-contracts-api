package com.zzzeinhom.contracts.ai.extractor;

import com.zzzeinhom.contracts.ai.ingestion.DocumentPayload;
import com.zzzeinhom.contracts.ai.ingestion.RoutedDocument;
import com.zzzeinhom.contracts.ai.ingestion.SmartDocumentIngestionRouter;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ContractStructuredExtractor {

    private final ChatClient chatClient;
    private final SmartDocumentIngestionRouter ingestionRouter;
    private final Resource userPromptResource;
    private final Resource clarificationPromptResource;
    private final Resource visionPromptResource;
    private final String visionModel;

    public ContractStructuredExtractor(
            ChatClient chatClient,
            SmartDocumentIngestionRouter ingestionRouter,
            @Value("classpath:/prompts/contract-extraction-user.st") Resource userPromptResource,
            @Value("classpath:/prompts/contract-clarification-user.st") Resource clarificationPromptResource,
            @Value("classpath:/prompts/contract-extraction-vision.st") Resource visionPromptResource,
            @Value("${app.ai.vision-model:${spring.ai.openai.chat.options.model:vision-model}}") String visionModel) {
        this.chatClient = chatClient;
        this.ingestionRouter = ingestionRouter;
        this.userPromptResource = userPromptResource;
        this.clarificationPromptResource = clarificationPromptResource;
        this.visionPromptResource = visionPromptResource;
        this.visionModel = visionModel;
    }

    public ExtractionOutcome extract(DocumentPayload payload) {
        RoutedDocument routed = ingestionRouter.route(payload);
        return switch (routed) {
            case RoutedDocument.TextDocument textDoc ->
                    new ExtractionOutcome(extractFromText(textDoc.extractedText()), textDoc.extractedText());
            case RoutedDocument.VisionDocument visionDoc -> {
                var outputConverter = new BeanOutputConverter<>(ExtractionResult.class);
                String format = outputConverter.getFormat();

                ExtractionResult result = chatClient.prompt()
                        .options(OpenAiChatOptions.builder()
                                .model(visionModel)
                                .temperature(0.0)
                                .build())
                        .user(u -> u.text(visionPromptResource)
                                .param("format", format)
                                .media(visionDoc.images().toArray(Media[]::new)))
                        .call()
                        .entity(outputConverter);

                yield new ExtractionOutcome(result, visionDoc.sourceSummary());
            }
        };
    }

    public ExtractionResult extractFromText(String rawContractText) {
        var outputConverter = new BeanOutputConverter<>(ExtractionResult.class);
        String format = outputConverter.getFormat();

        return chatClient.prompt()
                .user(u -> u.text(userPromptResource)
                        .param("format", format)
                        .param("contractText", rawContractText))
                .call()
                .entity(outputConverter);
    }

    public ExtractionResult refineWithClarifications(String currentResultJson, Map<String, String> answers) {
        var outputConverter = new BeanOutputConverter<>(ExtractionResult.class);
        String format = outputConverter.getFormat();
        String answersText = (answers == null || answers.isEmpty())
                ? "None"
                : answers.entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue())
                        .collect(Collectors.joining("\n"));

        return chatClient.prompt()
                .user(u -> u.text(clarificationPromptResource)
                        .param("currentJson", currentResultJson)
                        .param("answersText", answersText)
                        .param("format", format))
                .call()
                .entity(outputConverter);
    }

    public record ExtractionOutcome(
            ExtractionResult result,
            String persistedSourceText
    ) {}
}
