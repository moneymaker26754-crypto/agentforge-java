package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.util.List;

/**
 * A workflow job from the run jobs endpoint. stepsFailed contains the names of
 * the job's steps whose conclusion is "failure"; it is empty for healthy jobs.
 */
public record WorkflowJob(
        long id,
        String name,
        String conclusion,
        String startedAt,
        String completedAt,
        List<String> stepsFailed) {}
