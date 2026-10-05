package io.github.moneymaker26754.agentforge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moneymaker26754.agentforge.core.ChatRequest;
import io.github.moneymaker26754.agentforge.core.ModelClient;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.ToolCall;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Human-in-the-loop proof: the scripted model requests {@code fs_patch} (WRITE), the PreToolUse
 * gate suspends the session, the approval API approves it, and the resumed session applies the
 * patch and completes.
 */
@SpringBootTest(properties = {
        "agentforge.server.sandbox-mode=local",
        "agentforge.server.max-concurrent-tasks=2" })
@AutoConfigureMockMvc
class ApprovalFlowIntegrationTest {

    static {
        try {
            System.setProperty("agentforge.state.dir",
                    Files.createTempDirectory("agentforge-server-approval-").toString());
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
                    ModelResponse.toolCalls(List.of(new ToolCall("c1", 0, "fs_patch",
                            "{\"path\":\"target.txt\",\"expected\":\"old\",\"replacement\":\"new\"}")),
                            Usage.zero()),
                    ModelResponse.finalAnswer("patched the file", new Usage(8, 2, 0))));
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
    void approvalSuspendsResumesAndAppliesThePatch() throws Exception {
        Path repository = Files.createTempDirectory("agentforge-approval-repo-");
        Files.writeString(repository.resolve("target.txt"), "old");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("repository", repository.toString(), "task", "apply the fix"));

        String taskId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(
                        post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("taskId").asText();

        var waiting = awaitStatus(taskId, TaskStatus.WAITING_APPROVAL);
        assertThat(waiting.sessionId()).isNotBlank();

        String approvalId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(
                        get("/api/v1/approvals")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get(0).get("approvalId").asText();

        mvc.perform(post("/api/v1/approvals/" + approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE_ONCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(waiting.sessionId()));

        var done = awaitStatus(taskId, TaskStatus.COMPLETED);
        assertThat(Files.readString(repository.resolve("target.txt"))).isEqualTo("new");
        assertThat(done.sessionId()).isEqualTo(waiting.sessionId());
    }

    private io.github.moneymaker26754.agentforge.server.domain.AgentTask awaitStatus(String taskId,
            TaskStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var task = taskStore.findById(taskId).orElseThrow();
            if (task.status() == expected) {
                return task;
            }
            if (task.status() == TaskStatus.FAILED || task.status() == TaskStatus.BUDGET_EXHAUSTED
                    || task.status() == TaskStatus.UNCERTAIN) {
                throw new AssertionError("task failed instead of reaching " + expected + ": " + task.status());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("task did not reach " + expected + ": " + taskStore.findById(taskId));
    }
}
