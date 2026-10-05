package io.github.moneymaker26754.agentforge.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GitHubWebhookParserTest {
    private final GitHubWebhookParser parser = new GitHubWebhookParser(new ObjectMapper());

    @Test
    void parsesFailedWorkflowRunEvent() {
        String payload = """
                {"action":"completed","workflow_run":{
                  "id":424242,"name":"CI","conclusion":"failure","head_sha":"deadbeef",
                  "repository":{"full_name":"acme/widgets"}}}
                """;

        CiWebhookEvent event = parser.parse(payload);

        assertThat(event.repository()).isEqualTo("acme/widgets");
        assertThat(event.commitSha()).isEqualTo("deadbeef");
        assertThat(event.workflowRunId()).isEqualTo(424242);
        assertThat(event.workflowName()).isEqualTo("CI");
        assertThat(event.failedJobNames()).isEmpty();
    }

    @Test
    void ignoresSuccessfulWorkflowRun() {
        String payload = """
                {"action":"completed","workflow_run":{
                  "id":1,"name":"CI","conclusion":"success","head_sha":"deadbeef",
                  "repository":{"full_name":"acme/widgets"}}}
                """;

        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(GitHubWebhookIgnoreException.class);
    }

    @Test
    void ignoresWorkflowRunWithoutCompletedAction() {
        String payload = """
                {"action":"requested","workflow_run":{
                  "id":1,"name":"CI","conclusion":null,"head_sha":"deadbeef",
                  "repository":{"full_name":"acme/widgets"}}}
                """;

        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(GitHubWebhookIgnoreException.class);
    }

    @Test
    void parsesFailedWorkflowJobEvent() {
        String payload = """
                {"action":"completed","repository":{"full_name":"acme/widgets"},
                 "workflow_job":{
                   "id":77,"run_id":424242,"name":"test (17)","workflow_name":"CI",
                   "conclusion":"failure","head_sha":"cafebabe"}}
                """;

        CiWebhookEvent event = parser.parse(payload);

        assertThat(event.repository()).isEqualTo("acme/widgets");
        assertThat(event.commitSha()).isEqualTo("cafebabe");
        assertThat(event.workflowRunId()).isEqualTo(424242);
        assertThat(event.workflowName()).isEqualTo("CI");
        assertThat(event.failedJobNames()).containsExactly("test (17)");
    }

    @Test
    void allowsMissingHeadShaInWorkflowJobEvent() {
        String payload = """
                {"action":"completed","repository":{"full_name":"acme/widgets"},
                 "workflow_job":{"id":77,"run_id":424242,"name":"test","workflow_name":"CI","conclusion":"failure"}}
                """;

        CiWebhookEvent event = parser.parse(payload);

        assertThat(event.commitSha()).isNull();
        assertThat(event.workflowRunId()).isEqualTo(424242);
        assertThat(event.failedJobNames()).containsExactly("test");
    }

    @Test
    void ignoresSuccessfulWorkflowJob() {
        String payload = """
                {"action":"completed","repository":{"full_name":"acme/widgets"},
                 "workflow_job":{"id":1,"run_id":9,"name":"build","workflow_name":"CI","conclusion":"success"}}
                """;

        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(GitHubWebhookIgnoreException.class);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(GitHubWebhookIgnoreException.class);
    }

    @Test
    void rejectsUnrecognizedEvents() {
        assertThatThrownBy(() -> parser.parse("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .isInstanceOf(GitHubWebhookIgnoreException.class);
    }
}
