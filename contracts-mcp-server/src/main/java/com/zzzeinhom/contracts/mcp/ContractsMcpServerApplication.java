package com.zzzeinhom.contracts.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
        "com.zzzeinhom.contracts.mcp",
        "com.zzzeinhom.contracts.ai.embedding",
        "com.zzzeinhom.contracts.persistence"
})
@EntityScan("com.zzzeinhom.contracts.persistence.entity")
@EnableJpaRepositories("com.zzzeinhom.contracts.persistence.repository")
public class ContractsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractsMcpServerApplication.class, args);
    }
}
