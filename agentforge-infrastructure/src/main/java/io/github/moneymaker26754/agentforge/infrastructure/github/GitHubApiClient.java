package io.github.moneymaker26754.agentforge.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal GitHub REST API client covering the Actions endpoints needed for CI
 * failure diagnosis: workflow runs, failed jobs, job logs, commits and diffs.
 * All methods throw {@link GitHubApiException} for non-2xx responses and let
 * IO failures propagate as {@link IOException}.
 */
public final class GitHubApiClient {
    private static final String ACCEPT_HEADER = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "2022-11-28";
    private static final String TRUNCATION_MARKER = "...[truncated]...";
    private static final int MAX_DIFF_CHARS = 60_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String base;
    private final String token;

    public GitHubApiClient(HttpClient http, ObjectMapper mapper, URI baseUrl, String tokenNullable) {
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
        String raw = Objects.requireNonNull(baseUrl).toString();
        this.base = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.token = tokenNullable == null || tokenNullable.isEmpty() ? null : tokenNullable;
    }

    public List<WorkflowRun> listWorkflowRuns(String repository, String workflowFileName, int limit) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        String target = path("repos", ownerAndRepo[0], ownerAndRepo[1], "actions", "workflows", workflowFileName,
                "runs") + "?per_page=" + limit;
        return runsFrom(getJson(target).get("workflow_runs"));
    }

    public WorkflowRun getWorkflowRun(String repository, long runId) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        return runFrom(getJson(path("repos", ownerAndRepo[0], ownerAndRepo[1], "actions", "runs",
                Long.toString(runId))));
    }

    public List<WorkflowJob> getFailedJobs(String repository, long runId) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        JsonNode root = getJson(path("repos", ownerAndRepo[0], ownerAndRepo[1], "actions", "runs",
                Long.toString(runId), "jobs"));
        List<WorkflowJob> failed = new ArrayList<>();
        JsonNode jobs = root.get("jobs");
        if (jobs != null && jobs.isArray()) {
            for (JsonNode job : jobs) {
                if (!"failure".equals(textOrNull(job, "conclusion"))) {
                    continue;
                }
                failed.add(new WorkflowJob(job.path("id").asLong(), textOrNull(job, "name"), "failure",
                        textOrNull(job, "started_at"), textOrNull(job, "completed_at"), failedStepNames(job)));
            }
        }
        return List.copyOf(failed);
    }

    public String getJobLogs(String repository, long jobId, int maxChars) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        String target = path("repos", ownerAndRepo[0], ownerAndRepo[1], "actions", "jobs", Long.toString(jobId),
                "logs");
        HttpResponse<String> response = send(newRequest(target));
        if (response.statusCode() == 302) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                throw new GitHubApiException(response.statusCode(),
                        "GitHub log endpoint returned 302 without a Location header");
            }
            URI redirect = URI.create(location);
            if (!redirect.isAbsolute()) {
                redirect = URI.create(base).resolve(redirect);
            }
            // The signed download URL must not receive the Authorization header.
            response = send(HttpRequest.newBuilder(redirect).timeout(REQUEST_TIMEOUT).GET().build());
        }
        if (response.statusCode() / 100 != 2) {
            throw error(response);
        }
        return truncate(response.body(), maxChars);
    }

    public CommitInfo getCommit(String repository, String sha) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        JsonNode root = getJson(path("repos", ownerAndRepo[0], ownerAndRepo[1], "commits", sha));
        JsonNode commit = root.path("commit");
        String author = textOrNull(commit.path("author"), "name");
        if (author == null) {
            author = textOrNull(root.path("author"), "login");
        }
        List<String> changedFiles = new ArrayList<>();
        JsonNode files = root.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode file : files) {
                String filename = textOrNull(file, "filename");
                if (filename != null) {
                    changedFiles.add(filename);
                }
            }
        }
        return new CommitInfo(textOrNull(root, "sha"), textOrNull(commit, "message"), author, List.copyOf(changedFiles));
    }

    public String getDiff(String repository, String baseRef, String headRef) throws IOException {
        String[] ownerAndRepo = splitRepository(repository);
        String target = path("repos", ownerAndRepo[0], ownerAndRepo[1], "compare")
                + "/" + encodePathSegment(baseRef) + "..." + encodePathSegment(headRef);
        JsonNode root = getJson(target);
        StringBuilder diff = new StringBuilder();
        JsonNode files = root.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode file : files) {
                String patch = textOrNull(file, "patch");
                if (patch == null) {
                    continue;
                }
                if (!diff.isEmpty()) {
                    diff.append('\n');
                }
                diff.append(patch);
            }
        }
        return truncate(diff.toString(), MAX_DIFF_CHARS);
    }

    private List<WorkflowRun> runsFrom(JsonNode runs) {
        List<WorkflowRun> result = new ArrayList<>();
        if (runs != null && runs.isArray()) {
            for (JsonNode node : runs) {
                result.add(runFrom(node));
            }
        }
        return List.copyOf(result);
    }

    private static WorkflowRun runFrom(JsonNode node) {
        return new WorkflowRun(node.path("id").asLong(), textOrNull(node, "name"), textOrNull(node, "conclusion"),
                textOrNull(node, "status"), textOrNull(node, "head_sha"), textOrNull(node, "created_at"));
    }

    private static List<String> failedStepNames(JsonNode job) {
        List<String> names = new ArrayList<>();
        JsonNode steps = job.get("steps");
        if (steps != null && steps.isArray()) {
            for (JsonNode step : steps) {
                if ("failure".equals(textOrNull(step, "conclusion"))) {
                    String name = textOrNull(step, "name");
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private JsonNode getJson(String target) throws IOException {
        HttpResponse<String> response = send(newRequest(target));
        if (response.statusCode() / 100 != 2) {
            throw error(response);
        }
        return mapper.readTree(response.body());
    }

    private HttpRequest newRequest(String target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION_HEADER)
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub API request interrupted", exception);
        }
    }

    private GitHubApiException error(HttpResponse<String> response) {
        String detail = messageField(response.body());
        String message = "GitHub API returned HTTP " + response.statusCode();
        if (detail != null && !detail.isBlank()) {
            message += ": " + detail;
        }
        return new GitHubApiException(response.statusCode(), message);
    }

    private String messageField(String body) {
        try {
            String message = textOrNull(mapper.readTree(body), "message");
            return message == null ? abridge(body) : message;
        } catch (IOException invalidJson) {
            return abridge(body);
        }
    }

    private static String abridge(String text) {
        return text == null ? "" : text.substring(0, Math.min(200, text.length()));
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int markerLength = TRUNCATION_MARKER.length();
        if (maxChars <= markerLength) {
            return TRUNCATION_MARKER.substring(0, maxChars);
        }
        int headLength = (maxChars - markerLength) / 2;
        int tailLength = maxChars - markerLength - headLength;
        return text.substring(0, headLength) + TRUNCATION_MARKER + text.substring(text.length() - tailLength);
    }

    private String path(String... segments) {
        StringBuilder builder = new StringBuilder(base);
        for (String segment : segments) {
            builder.append('/').append(encodePathSegment(segment));
        }
        return builder.toString();
    }

    private static String[] splitRepository(String repository) {
        String[] parts = repository.split("/", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("repository must look like 'owner/name': " + repository);
        }
        return parts;
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
