package io.github.moneymaker26754.agentforge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moneymaker26754.agentforge.core.ChatRequest;
import io.github.moneymaker26754.agentforge.core.EventType;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.ToolCall;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end control-plane proof: a webhook-style task runs through the real engine, state machine
 * and stores, and finishes with a completed, audited session — without any external model or
 * network. The scripted model drives {@code fs_list} (READ) in the happy path.
 */
@SpringBootTest(properties = {
        "agentforge.server.sandbox-mode=local",
        "agentforge.server.max-concurrent-tasks=2",
        "agentforge.server.provider=deepseek" })
@AutoConfigureMockMvc
class ControlPlaneIntegrationTest {

    static {
        try {
            System.setProperty("agentforge.state.dir", Files.createTempDirectory("agentforge-server-it-").toString());
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean
        @Primary
        ModelClient scriptedModel() {
            var responses = new ArrayDeque<>(List.of(
                    ModelResponse.toolCalls(
                            List.of(new ToolCall("c1", 0, "fs_list", "{\"path\":\".\"}")), Usage.zero()),
                    ModelResponse.finalAnswer("inspected the workspace", new Usage(10, 2, 0))));
            return new ModelClient() {
                @Override
                public ModelResponse exchange(ChatRequest request, ModelDeltaSink sink) {
                    return responses.size() > 1 ? responses.removeFirst() : responses.getFirst();
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AgentTaskStore taskStore;

    @Test
    void taskRunsToCompletionWithAuditedSession() throws Exception {
        Path repository = Files.createTempDirectory("agentforge-repo-");
        Files.writeString(repository.resolve("README.md"), "hello");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("repository", repository.toString(), "task", "inspect the workspace"));

        MvcResult created = mvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andReturn();
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("taskId").asText();

        var task = awaitTerminal(taskId);
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.sessionId()).isNotBlank();

        mvc.perform(get("/api/v1/sessions/" + task.sessionId() + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='PHASE_ENTERED')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type=='TOOL_INTENT')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type=='TOOL_RESULT')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type=='SESSION_COMPLETED')]").isNotEmpty());

        mvc.perform(get("/api/v1/sessions/" + task.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCalls").value(1))
                .andExpect(jsonPath("$.sessions").value(1));
    }

    private io.github.moneymaker26754.agentforge.server.domain.AgentTask awaitTerminal(String taskId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var task = taskStore.findById(taskId).orElseThrow();
            if (task.status() != TaskStatus.CREATED && task.status() != TaskStatus.RUNNING) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("task did not reach a terminal status: " + taskStore.findById(taskId));
    }
}
