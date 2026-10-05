package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure-function assembler that turns a {@link CiFailureContext} into the minimal
 * necessary Markdown brief for a CI-diagnosis agent.
 *
 * <p>Design goals: keep the prompt small and free of irrelevant context, stay
 * deterministic (same input always yields the same output), and never let the
 * result exceed the requested token budget (approximated as {@code chars / 4},
 * consistent with the core {@code ContextManager} estimator).
 *
 * <p>Budget policy: after reserving characters for the fixed structure (header,
 * section frames, build command, instructions), the remaining budget is split by
 * section weight — Log 40%, Test Output 20%, Diff 20%, Stack Trace 10%, file
 * lists 10% (Changed Files and Related Files share the list share equally).
 * Each section body is truncated to its share, so the total character count is
 * at most {@code maxTokens * 4}. When {@code maxTokens} is so small that the
 * fixed structure alone exceeds the budget, the fixed structure is still emitted
 * verbatim (documented degenerate case).
 *
 * <p>This class is stateless and thread-safe; it depends only on {@code java.base}.
 */
public final class CiContextAssembler {

    /** Approximate characters per token, matching the core estimator ({@code text.length() / 4}). */
    public static final int CHARS_PER_TOKEN = 4;

    private static final int MAX_STACK_LINES = 40;
    private static final int MAX_LIST_LINES = 200;
    private static final int MAX_HEADER_VALUE_CHARS = 100;
    private static final int MAX_FAILED_JOBS_CHARS = 300;
    private static final int MAX_BUILD_COMMAND_CHARS = 200;
    private static final String NONE = "(none)";

    private static final String INSTRUCTIONS =
            "请用工具读取失败测试与相关代码，定位根因，应用最小补丁，运行定向测试验证，最后给出结论；不要修改与失败无关的文件";

    private static final Pattern EXCEPTION_LINE = Pattern.compile("^.*(?:Exception|Error)\\b.*$");
    private static final Pattern FRAME_LINE = Pattern.compile("^\\s*at\\s+.*$");

    public CiContextAssembler() {
    }

    /**
     * Approximates the token count of {@code text} as {@code max(1, length / 4)};
     * empty or null input counts as 0 tokens.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Assembles the minimal necessary context brief for {@code ctx}, truncated so
     * that the result stays within roughly {@code maxTokens} tokens (via
     * {@link #estimateTokens}). A null context is treated as a fully empty one.
     */
    public String assembleBrief(CiFailureContext ctx, int maxTokens) {
        CiFailureContext c = ctx == null ? emptyContext() : ctx;
        int max = Math.max(1, maxTokens);
        long charBudget = (long) max * CHARS_PER_TOKEN;

        String header = buildHeader(c);
        String buildBody = buildCommandBody(c);

        // Fixed structure: header, all section frames, build command body, instructions.
        long fixed = header.length()
                + sectionFrame("Failure Log Excerpt")
                + sectionFrame("Test Output")
                + sectionFrame("Stack Trace")
                + sectionFrame("Changed Files")
                + sectionFrame("Diff Excerpt")
                + sectionFrame("Related Files")
                + sectionFrame("Build Command")
                + sectionFrame("Instructions")
                + buildBody.length()
                + INSTRUCTIONS.length();

        long rem = Math.max(0L, charBudget - fixed);
        long logCap = rem * 40 / 100;
        long testCap = rem * 20 / 100;
        long diffCap = rem * 20 / 100;
        long stackCap = rem * 10 / 100;
        long listCap = rem - logCap - testCap - diffCap - stackCap; // 10% + rounding remainder
        long changedCap = listCap / 2;
        long relatedCap = listCap - changedCap;

        StringBuilder out = new StringBuilder();
        out.append(header);
        out.append(section("Failure Log Excerpt", body(c.jobLogExcerpt(), logCap)));
        out.append(section("Test Output", body(c.testOutput(), testCap)));
        out.append(section("Stack Trace", body(c.stackTrace(), stackCap)));
        out.append(section("Changed Files", listBody(c.changedFiles(), changedCap)));
        out.append(section("Diff Excerpt", body(c.diffExcerpt(), diffCap)));
        out.append(section("Related Files", listBody(c.relatedFiles(), relatedCap)));
        out.append(section("Build Command", buildBody));
        out.append(section("Instructions", INSTRUCTIONS));
        return out.toString();
    }

    /**
     * Extracts the first exception/error block from free-form text: the first line
     * containing {@code Exception} or {@code Error} plus the consecutive following
     * {@code at ...} frame lines (and chained exception lines), up to 40 lines total.
     * Returns {@code ""} when no such block exists.
     */
    public static String extractStackTrace(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        boolean inTrace = false;
        int count = 0;
        for (String line : lines) {
            if (!inTrace) {
                if (EXCEPTION_LINE.matcher(line).matches()) {
                    inTrace = true;
                    out.append(line);
                    count = 1;
                }
            } else if (count >= MAX_STACK_LINES) {
                break;
            } else if (FRAME_LINE.matcher(line).matches() || EXCEPTION_LINE.matcher(line).matches()) {
                out.append('\n').append(line);
                count++;
            } else {
                break;
            }
        }
        return out.toString();
    }

    /**
     * Truncates {@code text} to at most {@code maxChars} characters, keeping the
     * head and the tail and inserting a single {@code …} separator. Returns the
     * input unchanged when it already fits; returns {@code ""} for null input or
     * a non-positive limit.
     */
    public static String truncateMiddle(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars == 1) {
            return text.substring(0, 1);
        }
        int tail = maxChars / 2;
        int head = maxChars - tail - 1;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    /**
     * Keeps at most the first {@code maxLines} lines of {@code text} and then
     * truncates the result to at most {@code maxChars} characters (head/tail
     * preserved via {@link #truncateMiddle}).
     */
    public static String truncateLines(String text, int maxLines, int maxChars) {
        if (text == null || maxLines <= 0 || maxChars <= 0) {
            return "";
        }
        String joined = text.lines().limit(maxLines).collect(Collectors.joining("\n"));
        return truncateMiddle(joined, maxChars);
    }

    // ---------------------------------------------------------------- internals

    private static CiFailureContext emptyContext() {
        return new CiFailureContext(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String buildHeader(CiFailureContext c) {
        StringBuilder sb = new StringBuilder("# CI Failure Brief");
        appendField(sb, "Repository", c.repository());
        appendField(sb, "Commit", c.commitSha());
        appendField(sb, "Workflow", c.workflowName());
        Long run = c.workflowRunId();
        if (run != null && run.longValue() != 0L) {
            sb.append("\n- Run: ").append(run);
        }
        if (!c.failedJobNames().isEmpty()) {
            String jobs = truncateMiddle(String.join(", ", c.failedJobNames()), MAX_FAILED_JOBS_CHARS);
            sb.append("\n- Failed Jobs: ").append(jobs);
        }
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("\n- ").append(label).append(": ")
                    .append(truncateMiddle(value, MAX_HEADER_VALUE_CHARS));
        }
    }

    /** "{@code \n## Title\n<body>\n}" — the leading blank line separates sections. */
    private static String section(String title, String body) {
        return "\n## " + title + "\n" + body + "\n";
    }

    /** Character count of a section's fixed frame: {@code "\n## Title\n\n"}. */
    private static long sectionFrame(String title) {
        return 5L + title.length();
    }

    private static String body(String text, long capChars) {
        int cap = toIntCap(capChars);
        if (cap <= 0) {
            return "";
        }
        String value = (text == null || text.isEmpty()) ? NONE : truncateMiddle(text, cap);
        return cap(value, cap);
    }

    private static String listBody(List<String> items, long capChars) {
        int cap = toIntCap(capChars);
        if (items == null || items.isEmpty()) {
            return cap <= 0 ? "" : cap(NONE, cap);
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
        return cap(truncateLines(sb.toString(), MAX_LIST_LINES, Math.max(1, cap)), cap);
    }

    private static String buildCommandBody(CiFailureContext c) {
        String cmd = c.buildCommand();
        if (cmd == null || cmd.isEmpty()) {
            return NONE;
        }
        return truncateMiddle(cmd, MAX_BUILD_COMMAND_CHARS);
    }

    private static int toIntCap(long capChars) {
        return (int) Math.min(capChars, Integer.MAX_VALUE);
    }

    /** Hard guarantee: never returns a string longer than {@code cap}. */
    private static String cap(String value, int cap) {
        return value.length() <= cap ? value : value.substring(0, cap);
    }
}
