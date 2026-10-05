package io.github.moneymaker26754.agentforge.server.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moneymaker26754.agentforge.infrastructure.github.CommitInfo;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubWebhookVerifier;
import io.github.moneymaker26754.agentforge.server.config.ServerProperties;
import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.runtime.TaskExecutionCoordinator;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebhookController.class)
@Import(WebhookControllerTest.Config.class)
@TestPropertySource(properties = "agentforge.server.webhook-secret=test-secret")
class WebhookControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(ServerProperties.class)
    static class Config {
        @org.springframework.context.annotation.Bean
        io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler ciContextAssembler() {
            return new io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean GitHubApiClient github;
    @MockitoBean AgentTaskStore taskStore;
    @MockitoBean TaskExecutionCoordinator coordinator;
    @MockitoBean Clock clock;

    private static final String FAILED_RUN = """
            {"action":"completed","workflow_run":{"id":123,"name":"ci","conclusion":"failure","head_sha":"abc123"},
             "repository":{"full_name":"owner/repo"}}
            """;

    private String signed(String body) {
        return GitHubWebhookVerifier.sign(body.getBytes(StandardCharsets.UTF_8), "test-secret");
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        mvc.perform(post("/api/v1/webhooks/github")
                        .content(FAILED_RUN).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsTaskForFailedWorkflowRunAndSchedulesIt() throws Exception {
        when(github.getFailedJobs("owner/repo", 123L)).thenReturn(List.of());
        when(github.getCommit("owner/repo", "abc123")).thenReturn(new CommitInfo("abc123", "msg", "dev", List.of()));
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(taskStore.findByKey("owner/repo", "abc123", 123L)).thenReturn(Optional.empty());
        when(taskStore.insertIdempotent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/v1/webhooks/github")
                        .content(FAILED_RUN).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signed(FAILED_RUN)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.taskId").isNotEmpty());

        verify(coordinator).submit(any(AgentTask.class));
    }

    @Test
    void replayOfSameWebhookReturnsOriginalTaskWithoutScheduling() throws Exception {
        AgentTask existing = AgentTask.create("task-1", "owner/repo", "abc123", 123L, "prompt",
                "2026-09-29T00:00:00Z");
        when(taskStore.findByKey("owner/repo", "abc123", 123L)).thenReturn(Optional.of(existing));

        mvc.perform(post("/api/v1/webhooks/github")
                        .content(FAILED_RUN).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signed(FAILED_RUN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.taskId").value("task-1"));

        verify(coordinator, org.mockito.Mockito.never()).submit(any());
    }

    @Test
    void acknowledgesSuccessfulWorkflowRunsWithoutCreatingTasks() throws Exception {
        String success = """
                {"action":"completed","workflow_run":{"id":124,"name":"ci","conclusion":"success","head_sha":"abc"},
                 "repository":{"full_name":"owner/repo"}}
                """;
        mvc.perform(post("/api/v1/webhooks/github")
                        .content(success).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signed(success)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true));
    }

    @Test
    void createsTaskForFailedWorkflowJob() throws Exception {
        String failedJob = """
                {"action":"completed","workflow_job":{"id":7,"run_id":123,"name":"tests","conclusion":"failure"},
                 "repository":{"full_name":"owner/repo"}}
                """;
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(taskStore.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(taskStore.insertIdempotent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/v1/webhooks/github")
                        .content(failedJob).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signed(failedJob)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.created").value(true));
    }
}
