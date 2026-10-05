package io.github.moneymaker26754.agentforge.server.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.RunResult;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.TerminationReason;
import io.github.moneymaker26754.agentforge.core.Usage;
import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.runtime.ServerAgentRuntime;
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

@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AgentTaskStore taskStore;
    @MockitoBean ServerAgentRuntime runtime;
    @MockitoBean Clock clock;

    private static PendingApprovalRow row(String decision) {
        return new PendingApprovalRow("a1", "s1", "gitCommit", "{}", "confirm", "2026-09-29T00:00:00Z",
                decision, decision == null ? null : "2026-09-29T00:01:00Z");
    }

    @Test
    void approvesAndResumesSession() throws Exception {
        when(taskStore.findApproval("a1")).thenReturn(Optional.of(row(null)));
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(runtime.resume(new SessionId("s1"), ApprovalDecision.APPROVE_ONCE)).thenReturn(new RunResult(
                new SessionId("s1"), RunStatus.COMPLETED, "done", Usage.zero(), TerminationReason.FINAL_ANSWER, 2));
        AgentTask task = AgentTask.create("t1", "r", "c", 1L, "p", "now");
        when(taskStore.findBySessionId("s1")).thenReturn(Optional.of(task));

        mvc.perform(post("/api/v1/approvals/a1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE_ONCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value("a1"))
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(taskStore).decideApproval(eq("a1"), eq("APPROVE_ONCE"), any());
        verify(taskStore).update(any(AgentTask.class));
    }

    @Test
    void rejectResumesSessionWithRejection() throws Exception {
        when(taskStore.findApproval("a1")).thenReturn(Optional.of(row(null)));
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(runtime.resume(new SessionId("s1"), ApprovalDecision.REJECT)).thenReturn(new RunResult(
                new SessionId("s1"), RunStatus.COMPLETED, "done", Usage.zero(), TerminationReason.FINAL_ANSWER, 2));

        mvc.perform(post("/api/v1/approvals/a1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(taskStore).decideApproval(eq("a1"), eq("REJECT"), any());
    }

    @Test
    void unknownApprovalIsBadRequest() throws Exception {
        when(taskStore.findApproval("missing")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/approvals/missing")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE_ONCE\"}"))
                .andExpect(status().isBadRequest());

        verify(runtime, never()).resume(any(), any());
    }

    @Test
    void alreadyDecidedApprovalConflicts() throws Exception {
        when(taskStore.findApproval("a1")).thenReturn(Optional.of(row("APPROVE_ONCE")));

        mvc.perform(post("/api/v1/approvals/a1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isConflict());

        verify(runtime, never()).resume(any(), any());
    }

    @Test
    void invalidDecisionIsBadRequest() throws Exception {
        when(taskStore.findApproval("a1")).thenReturn(Optional.of(row(null)));

        mvc.perform(post("/api/v1/approvals/a1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsPendingApprovals() throws Exception {
        when(taskStore.listPendingApprovals(50)).thenReturn(List.of(row(null)));

        mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approvalId").value("a1"));
    }
}
