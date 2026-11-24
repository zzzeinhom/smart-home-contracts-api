package com.zzzeinhom.contracts.ai.quotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QuotationGeneratorAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final Resource decompositionPromptResource;
    private final Resource assemblyPromptResource;

    public QuotationGeneratorAgent(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            @Value("classpath:/prompts/quotation-decomposition-user.st") Resource decompositionPromptResource,
            @Value("classpath:/prompts/quotation-assembly-user.st") Resource assemblyPromptResource,
            @Value("${spring.ai.openai.chat.options.model:chat-model}") String chatModel) {
        this.chatClient = chatClientBuilder
                .defaultSystem("You are a Senior Smart Home Pre-Sales Architect in Egypt. "
                        + "Decompose site surveys accurately and ground Bills of Materials strictly against provided catalog candidates, returning ONLY valid JSON.")
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModel)
                        .temperature(0.0)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.decompositionPromptResource = decompositionPromptResource;
        this.assemblyPromptResource = assemblyPromptResource;
    }

    public SurveyDecomposition decomposeSurvey(String surveyNotes) {
        if (surveyNotes == null || surveyNotes.isBlank()) {
            throw new IllegalArgumentException("Site survey notes must not be blank.");
        }
        var outputConverter = new BeanOutputConverter<>(SurveyDecomposition.class);
        String format = outputConverter.getFormat();

        return chatClient.prompt()
                .user(u -> u.text(decompositionPromptResource)
                        .param("format", format)
                        .param("surveyNotes", surveyNotes))
                .call()
                .entity(outputConverter);
    }

    public List<BillOfMaterialsItem> assembleGroundedBom(List<GroundedRequirement> groundedRequirements) {
        if (groundedRequirements == null || groundedRequirements.isEmpty()) {
            return List.of();
        }
        String groundedRequirementsJson;
        try {
            groundedRequirementsJson = objectMapper.writeValueAsString(groundedRequirements);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize grounded requirements for quotation assembly.", ex);
        }

        var outputConverter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<BillOfMaterialsItem>>() {});
        String format = outputConverter.getFormat();

        List<BillOfMaterialsItem> items = chatClient.prompt()
                .user(u -> u.text(assemblyPromptResource)
                        .param("groundedRequirementsJson", groundedRequirementsJson)
                        .param("format", format))
                .call()
                .entity(outputConverter);

        return items != null ? items : List.of();
    }
}
