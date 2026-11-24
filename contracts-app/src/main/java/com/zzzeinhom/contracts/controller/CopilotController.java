package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.dto.CopilotChatRequest;
import com.zzzeinhom.contracts.dto.CopilotChatResponse;
import com.zzzeinhom.contracts.service.OperationsCopilotService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {

    private final OperationsCopilotService operationsCopilotService;

    public CopilotController(OperationsCopilotService operationsCopilotService) {
        this.operationsCopilotService = operationsCopilotService;
    }

    @PostMapping("/chat")
    public ResponseEntity<CopilotChatResponse> chat(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @Valid @RequestBody CopilotChatRequest request) {
        CopilotChatResponse response = operationsCopilotService.chat(
                tenantId,
                request.conversationId(),
                request.message());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/chat/{conversationId}")
    public ResponseEntity<Void> clearConversation(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String conversationId) {
        operationsCopilotService.clearConversation(tenantId, conversationId);
        return ResponseEntity.noContent().build();
    }
}
