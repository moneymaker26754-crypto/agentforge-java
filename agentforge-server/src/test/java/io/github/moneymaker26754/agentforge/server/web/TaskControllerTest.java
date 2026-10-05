package io.github.moneymaker26754.agentforge.server.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.runtime.TaskExecutionCoordinator;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AgentTaskStore taskStore;
    @MockitoBean TaskExecutionCoordinator coordinator;
    @MockitoBean Clock clock;

    @Test
    void createsManualTaskAndSchedulesIt() throws Exception {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(taskStore.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(taskStore.insertIdempotent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"owner/repo\",\"task\":\"fix failing tests\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.taskId").isNotEmpty());

        verify(coordinator).submit(any(AgentTask.class));
    }

    @Test
    void duplicateWebhookStyleTaskReturnsExisting() throws Exception {
        AgentTask existing = AgentTask.create("task-1", "owner/repo", "abc", 42L, "p",
                "2026-09-29T00:00:00Z");
        when(taskStore.findByKey("owner/repo", "abc", 42L)).thenReturn(Optional.of(existing));

        mvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"owner/repo\",\"commitSha\":\"abc\",\"workflowRunId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.taskId").value("task-1"));

        verify(coordinator, org.mockito.Mockito.never()).submit(any());
    }

    @Test
    void listsTasks() throws Exception {
        when(taskStore.list(50)).thenReturn(List.of(
                AgentTask.create("t1", "r", "c", 1L, "p", "now")));

        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t1"));
    }

    @Test
    void returnsTaskOrNotFound() throws Exception {
        when(taskStore.findById("t1")).thenReturn(Optional.of(
                AgentTask.create("t1", "r", "c", 1L, "p", "now")));

        mvc.perform(get("/api/v1/tasks/t1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"));
        mvc.perform(get("/api/v1/tasks/missing")).andExpect(status().isNotFound());
    }
}
