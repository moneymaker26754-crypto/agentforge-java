package io.github.moneymaker26754.agentforge.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CiContextAssemblerTest {

    private final CiContextAssembler assembler = new CiContextAssembler();

    @Test
    void assemblesAllSectionsInFixedOrderWithKeyEvidence() {
        String brief = assembler.assembleBrief(fullContext(), 8000);

        assertThat(brief).contains(
                "# CI Failure Brief",
                "- Repository: moneymaker26754/agentforge-java",
                "- Commit: abc123def456",
                "- Workflow: CI",
                "- Run: 42",
                "- Failed Jobs: build, test",
                "## Failure Log Excerpt",
                "## Test Output",
                "## Stack Trace",
                "## Changed Files",
                "## Diff Excerpt",
                "## Related Files",
                "## Build Command",
                "## Instructions",
                "NullPointerException",
                "cannot find symbol",
                "Tests run: 12, Failures: 1",
                "- src/main/java/com/example/UserService.java",
                "- src/main/java/com/example/UserRepository.java",
                "./mvnw -B verify",
                "不要修改与失败无关的文件");

        String[] sections = {
                "## Failure Log Excerpt", "## Test Output", "## Stack Trace", "## Changed Files",
                "## Diff Excerpt", "## Related Files", "## Build Command", "## Instructions"};
        int previous = -1;
        for (String section : sections) {
            int at = brief.indexOf(section);
            assertThat(at).as("section order: %s", section).isGreaterThan(previous);
            previous = at;
        }
    }

    @Test
    void staysWithinTokenBudgetAcrossBudgetsAndTruncatesLargeLog() {
        CiFailureContext ctx = bigContext();
        for (int maxTokens : new int[] {500, 2000, 8000}) {
            String brief = assembler.assembleBrief(ctx, maxTokens);
            assertThat(assembler.estimateTokens(brief))
                    .as("estimated tokens with maxTokens=%d", maxTokens)
                    .isLessThanOrEqualTo(maxTokens);
            assertThat(brief).contains("# CI Failure Brief", "MARKER_KEPT");
            assertThat(brief).doesNotContain("MIDDLE_MARKER_MUST_DISAPPEAR");
        }
    }

    @Test
    void handlesEmptyAndNullContextWithoutThrowing() {
        CiFailureContext empty = new CiFailureContext(
                null, null, null, null, null, null, null, null, null, null, null, null);
        String brief = assembler.assembleBrief(empty, 1000);

        assertThat(brief).contains(
                "# CI Failure Brief", "## Failure Log Excerpt", "## Instructions", "不要修改与失败无关的文件");
        assertThat(brief).doesNotContain(
                "- Repository:", "- Commit:", "- Workflow:", "- Run:", "- Failed Jobs:");
        assertThat(assembler.assembleBrief(null, 1000)).isEqualTo(brief);
        assertThat(assembler.estimateTokens(brief)).isLessThanOrEqualTo(1000);
    }

    @Test
    void recordDefendsAgainstNullsAndIsImmutable() {
        List<String> jobs = new ArrayList<>(Arrays.asList("build", null, "test"));
        CiFailureContext ctx = new CiFailureContext(
                null, null, null, null, jobs, null, null, null, null, null, null, null);

        assertThat(ctx.repository()).isEmpty();
        assertThat(ctx.workflowRunId()).isNull();
        assertThat(ctx.jobLogExcerpt()).isEmpty();
        assertThat(ctx.failedJobNames()).containsExactly("build", "test");
        assertThat(ctx.changedFiles()).isEmpty();
        assertThatThrownBy(() -> ctx.failedJobNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isDeterministic() {
        CiFailureContext ctx = fullContext();
        assertThat(assembler.assembleBrief(ctx, 1234)).isEqualTo(assembler.assembleBrief(ctx, 1234));
        assertThat(assembler.assembleBrief(ctx, 500)).isEqualTo(assembler.assembleBrief(ctx, 500));
        assertThat(assembler.assembleBrief(bigContext(), 2000)).isEqualTo(assembler.assembleBrief(bigContext(), 2000));
    }

    @Test
    void extractStackTraceFindsFirstExceptionBlockAndCapsAt40Lines() {
        StringBuilder sb = new StringBuilder("java.lang.NullPointerException: boom");
        for (int i = 0; i < 50; i++) {
            sb.append("\n\tat com.example.Frame")
                    .append(String.format("%02d", i))
                    .append("(Frame.java:")
                    .append(i)
                    .append(")");
        }
        String extracted = CiContextAssembler.extractStackTrace(sb.toString());

        assertThat(extracted).startsWith("java.lang.NullPointerException: boom");
        assertThat(extracted.split("\n")).hasSize(40);
        assertThat(extracted).contains("Frame00", "Frame38").doesNotContain("Frame39", "Frame49");
    }

    @Test
    void extractStackTraceSkipsLeadingNoiseAndStopsAtUnrelatedLines() {
        String noisy = """
                [INFO] building project
                ERROR java.lang.IllegalStateException: bad state
                \tat com.example.App.run(App.java:10)
                \tat com.example.Main.main(Main.java:5)
                BUILD FAILED in 3s""";
        String extracted = CiContextAssembler.extractStackTrace(noisy);

        assertThat(extracted).isEqualTo(
                "ERROR java.lang.IllegalStateException: bad state\n"
                        + "\tat com.example.App.run(App.java:10)\n"
                        + "\tat com.example.Main.main(Main.java:5)");
        assertThat(extracted).doesNotContain("BUILD FAILED");
    }

    @Test
    void extractStackTraceReturnsEmptyWhenNoStackPresent() {
        assertThat(CiContextAssembler.extractStackTrace("all tests passed")).isEmpty();
        assertThat(CiContextAssembler.extractStackTrace("Build succeeded")).isEmpty();
        assertThat(CiContextAssembler.extractStackTrace("")).isEmpty();
        assertThat(CiContextAssembler.extractStackTrace(null)).isEmpty();
    }

    @Test
    void truncatesManyChangedFilesToBudget() {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            files.add(String.format("src/main/java/com/example/Module%04d.java", i));
        }
        CiFailureContext ctx = new CiFailureContext(
                "r/r", "abc", 1L, "CI", List.of("build"),
                null, null, null, files, null, List.of(), "./mvnw -B verify");
        String brief = assembler.assembleBrief(ctx, 500);

        assertThat(brief).contains("## Changed Files");
        assertThat(brief).doesNotContain("Module0250.java");
        int moduleOccurrences = brief.split("Module", -1).length - 1;
        assertThat(moduleOccurrences).isLessThan(500);
        assertThat(assembler.estimateTokens(brief)).isLessThanOrEqualTo(500);
    }

    @Test
    void truncateMiddleKeepsHeadAndTailWithinLimit() {
        assertThat(CiContextAssembler.truncateMiddle("abcdefghij", 6)).isEqualTo("ab…hij");
        assertThat(CiContextAssembler.truncateMiddle("short", 100)).isEqualTo("short");
        assertThat(CiContextAssembler.truncateMiddle("short", 5)).isEqualTo("short");
        assertThat(CiContextAssembler.truncateMiddle("anything", 0)).isEmpty();
        assertThat(CiContextAssembler.truncateMiddle(null, 10)).isEmpty();
    }

    @Test
    void truncateLinesLimitsLineCountAndCharacters() {
        String multi = "line1\nline2\nline3\nline4";
        assertThat(CiContextAssembler.truncateLines(multi, 2, 100)).isEqualTo("line1\nline2");
        assertThat(CiContextAssembler.truncateLines(multi, 2, 8)).isEqualTo("lin…ine2");
        assertThat(CiContextAssembler.truncateLines(null, 2, 10)).isEmpty();
    }

    // ------------------------------------------------------------ fixtures

    private static CiFailureContext fullContext() {
        String log = """
                [INFO] Compiling 12 source files
                [ERROR] COMPILATION ERROR :
                [ERROR] /home/runner/work/repo/repo/src/main/java/com/example/UserService.java:[42,20] cannot find symbol
                [INFO] BUILD FAILURE in 12s""";
        String testOutput = """
                [INFO] Running com.example.UserServiceTest
                Tests run: 12, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] UserServiceTest.getName_whenNull_shouldThrow:42 expected <NPE> but was <null>""";
        String stack = """
                java.lang.NullPointerException: Cannot invoke "String.length()" because "name" is null
                        at com.example.service.UserService.getName(UserService.java:42)
                        at com.example.service.UserServiceTest.getName_whenNull_shouldThrow(UserServiceTest.java:58)""";
        String diff = """
                --- a/src/main/java/com/example/UserService.java
                +++ b/src/main/java/com/example/UserService.java
                @@ -40,6 +40,7 @@
                     public String getName() {
                +        if (name == null) return "";
                         return name.trim();
                     }""";
        return new CiFailureContext(
                "moneymaker26754/agentforge-java", "abc123def456", 42L, "CI",
                List.of("build", "test"), log, testOutput, stack,
                List.of(
                        "src/main/java/com/example/UserService.java",
                        "src/test/java/com/example/UserServiceTest.java",
                        "pom.xml"),
                diff,
                List.of(
                        "src/main/java/com/example/UserRepository.java",
                        "src/main/java/com/example/UserController.java"),
                "./mvnw -B verify");
    }

    private static CiFailureContext bigContext() {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            log.append(String.format("log line %04d filler text to keep every line long enough", i));
            if (i == 1 || i == 2000) {
                log.append(" MARKER_KEPT");
            }
            if (i == 1000) {
                log.append(" MIDDLE_MARKER_MUST_DISAPPEAR");
            }
            log.append('\n');
        }
        String testOutput = "Tests run: 5000, Failures: 5000\n" + "test failure line ".repeat(2000);
        StringBuilder stack = new StringBuilder("java.lang.OutOfMemoryError: Java heap space");
        for (int i = 0; i < 2000; i++) {
            stack.append("\n\tat com.example.DeepFrame").append(i).append("(Deep.java:").append(i).append(")");
        }
        String diff = "@@ diff line @@\n" + "+    added filler line\n".repeat(2000);
        List<String> changedFiles = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            changedFiles.add(String.format("src/main/java/com/example/Big%04d.java", i));
        }
        return new CiFailureContext(
                "moneymaker26754/agentforge-java", "deadbeef", 7L, "CI",
                List.of("build", "test"), log.toString(), testOutput, stack.toString(),
                changedFiles, diff, List.of("rel/One.java", "rel/Two.java"), "./mvnw -B verify");
    }
}
