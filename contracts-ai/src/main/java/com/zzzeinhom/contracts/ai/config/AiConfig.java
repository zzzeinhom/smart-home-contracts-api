package com.zzzeinhom.contracts.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are an expert Smart Home Contract Analyzer and Estimator. Your job is to extract client details, financial installments, and a bill of materials (BoM) from mixed Arabic/English technical quotes and return ONLY valid JSON.")
                .build();
    }
}
