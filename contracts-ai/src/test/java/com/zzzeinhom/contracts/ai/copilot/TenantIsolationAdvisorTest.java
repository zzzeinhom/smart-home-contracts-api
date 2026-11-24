package com.zzzeinhom.contracts.ai.copilot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantIsolationAdvisorTest {

    private TenantIsolationAdvisor advisor;

    @Mock
    private CallAdvisorChain callAdvisorChain;

    @BeforeEach
    void setUp() {
        advisor = new TenantIsolationAdvisor();
    }

    @Test
    void adviseCall_throwsIllegalStateExceptionWhenTenantIdMissingBeforeCallingChain() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("What pending installments do we have?"))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "conv-1"))
                .build();

        assertThatThrownBy(() -> advisor.adviseCall(request, callAdvisorChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TenantIsolationAdvisor.TENANT_ID_KEY);

        verifyNoInteractions(callAdvisorChain);
    }

    @Test
    void adviseCall_throwsIllegalStateExceptionWhenTenantIdBlankBeforeCallingChain() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("Aggregate procurement BoM"))
                .context(Map.of(
                        TenantIsolationAdvisor.TENANT_ID_KEY, "   ",
                        ChatMemory.CONVERSATION_ID, "conv-1"))
                .build();

        assertThatThrownBy(() -> advisor.adviseCall(request, callAdvisorChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TenantIsolationAdvisor.TENANT_ID_KEY);

        verifyNoInteractions(callAdvisorChain);
    }

    @Test
    void adviseCall_namespacesConversationIdWithNormalizedTenantId() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("Summarize New Cairo villa contract"))
                .context(Map.of(
                        TenantIsolationAdvisor.TENANT_ID_KEY, "Smart-Power",
                        ChatMemory.CONVERSATION_ID, "conv-1"))
                .build();

        ChatClientResponse expectedResponse = mock(ChatClientResponse.class);
        when(callAdvisorChain.nextCall(any(ChatClientRequest.class))).thenReturn(expectedResponse);

        ChatClientResponse actualResponse = advisor.adviseCall(request, callAdvisorChain);

        assertThat(actualResponse).isSameAs(expectedResponse);

        ArgumentCaptor<ChatClientRequest> requestCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(callAdvisorChain).nextCall(requestCaptor.capture());

        ChatClientRequest downstreamRequest = requestCaptor.getValue();
        assertThat(downstreamRequest.context().get(ChatMemory.CONVERSATION_ID))
                .isEqualTo("smart-power:conv-1");
        assertThat(downstreamRequest.context().get(TenantIsolationAdvisor.TENANT_ID_KEY))
                .isEqualTo("smart-power");
    }

    @Test
    void adviseCall_defaultsConversationIdWhenOmitted() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("Check catalog for Aqara switches"))
                .context(Map.of(TenantIsolationAdvisor.TENANT_ID_KEY, "Smart-Power"))
                .build();

        ChatClientResponse expectedResponse = mock(ChatClientResponse.class);
        when(callAdvisorChain.nextCall(any(ChatClientRequest.class))).thenReturn(expectedResponse);

        advisor.adviseCall(request, callAdvisorChain);

        ArgumentCaptor<ChatClientRequest> requestCaptor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(callAdvisorChain).nextCall(requestCaptor.capture());

        assertThat(requestCaptor.getValue().context().get(ChatMemory.CONVERSATION_ID))
                .isEqualTo("smart-power:default");
    }

    @Test
    void getOrder_runsBeforeMessageChatMemoryAdvisor() {
        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }
}
