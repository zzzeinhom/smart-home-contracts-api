package com.zzzeinhom.contracts.dto;

import jakarta.validation.constraints.NotBlank;

public record CopilotChatRequest(
        String conversationId,
        @NotBlank(message = "message must not be blank") String message
) {
}
