package com.zzzeinhom.contracts.dto;

import java.time.Instant;

public record CopilotChatResponse(
        String conversationId,
        String reply,
        Instant timestamp
) {
}
