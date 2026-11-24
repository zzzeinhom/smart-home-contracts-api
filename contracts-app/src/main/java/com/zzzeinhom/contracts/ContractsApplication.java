package com.zzzeinhom.contracts;

import org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = SseHttpClientTransportAutoConfiguration.class)
public class ContractsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractsApplication.class, args);
    }
}
