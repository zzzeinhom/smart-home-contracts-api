package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.dto.CopilotChatRequest;
import com.zzzeinhom.contracts.dto.CopilotChatResponse;
import com.zzzeinhom.contracts.security.TenantContext;
import com.zzzeinhom.contracts.service.OperationsCopilotService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public ResponseEntity<CopilotChatResponse> chat(@Valid @RequestBody CopilotChatRequest request) {
        String tenantId = TenantContext.requireTenantId();
        CopilotChatResponse response = operationsCopilotService.chat(
                tenantId,
                request.conversationId(),
                request.message());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/chat/{conversationId}")
    public ResponseEntity<Void> clearConversation(@PathVariable String conversationId) {
        String tenantId = TenantContext.requireTenantId();
        operationsCopilotService.clearConversation(tenantId, conversationId);
        return ResponseEntity.noContent().build();
    }
}
