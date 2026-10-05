package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.ProviderId;
import io.github.moneymaker26754.agentforge.core.RunStatus;
import io.github.moneymaker26754.agentforge.core.SandboxMode;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiFailureContext;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * CI failure diagnosis entry point: fetch the failed workflow run, jobs, logs, commit and diff
 * through the GitHub adapter, assemble a minimal evidence brief with {@link CiContextAssembler},
 * and hand it to the agent loop against a local checkout.
 */
@Command(name = "ci", description = "Diagnose a CI failure against a local checkout",
        mixinStandardHelpOptions = true)
public final class CiCommand implements Callable<Integer> {
    private static final int CONTEXT_TOKEN_BUDGET = 4_000;

    private final AgentOperations operations;
    private final GitHubApiClient github;
    private final CiContextAssembler assembler;
    @Spec private Model.CommandSpec spec;

    @Option(names = "--repo", required = true) private Path repository;
    @Option(names = "--github-repo", description = "owner/repo slug for GitHub API calls") private String githubRepo;
    @Option(names = "--run", description = "failed workflow run id") private Long runId;
    @Option(names = "--task", description = "manual failure summary (used when --run is absent)") private String task;
    @Option(names = "--provider", defaultValue = "deepseek") private String provider;
    @Option(names = "--sandbox", defaultValue = "docker") private String sandbox;

    public CiCommand(AgentOperations operations, GitHubApiClient github, CiContextAssembler assembler) {
        this.operations = operations;
        this.github = github;
        this.assembler = assembler;
    }

    @Override public Integer call() {
        String brief;
        if (runId != null) {
            if (githubRepo == null || githubRepo.isBlank()) {
                throw new IllegalArgumentException("--github-repo is required with --run");
            }
            brief = assembleFromRun();
        } else {
            if (task == null || task.isBlank()) {
                throw new IllegalArgumentException("either --run or --task is required");
            }
            brief = task;
        }
        var result = operations.run(repository, brief,
                ProviderId.valueOf(provider.toUpperCase(Locale.ROOT)),
                SandboxMode.valueOf(sandbox.toUpperCase(Locale.ROOT)));
        spec.commandLine().getOut().printf("session=%s%nstatus=%s%nanswer=%s%n", result.sessionId(),
                result.status(), result.answer());
        return result.status() == RunStatus.COMPLETED ? 0 : 1;
    }

    private String assembleFromRun() {
        try {
            var run = github.getWorkflowRun(githubRepo, runId);
            var failedJobs = github.getFailedJobs(githubRepo, runId);
            StringBuilder logs = new StringBuilder();
            for (var job : failedJobs) {
                logs.append("== job ").append(job.name()).append(" ==\n")
                        .append(github.getJobLogs(githubRepo, job.id(), 8_000)).append('\n');
            }
            var changed = run.headSha() == null || run.headSha().isBlank()
                    ? List.<String>of()
                    : github.getCommit(githubRepo, run.headSha()).changedFiles();
            String diff = "";
            if (run.headSha() != null && !run.headSha().isBlank()) {
                try {
                    diff = github.getDiff(githubRepo, run.headSha() + "^", run.headSha());
                } catch (Exception ignored) {
                    // Evidence is best-effort; the brief stays useful without the diff.
                }
            }
            var context = new CiFailureContext(githubRepo, run.headSha(), runId, run.name(),
                    failedJobs.stream().map(job -> job.name()).toList(), logs.toString(), "",
                    CiContextAssembler.extractStackTrace(logs.toString()), changed, diff, List.of(), "");
            return assembler.assembleBrief(context, CONTEXT_TOKEN_BUDGET);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot assemble CI context for run " + runId + ": "
                    + exception.getMessage(), exception);
        }
    }
}
