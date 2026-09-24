package io.github.moneymaker26754.agentforge.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.eval.BenchmarkReport;
import io.github.moneymaker26754.agentforge.eval.BenchmarkReportWriter;
import io.github.moneymaker26754.agentforge.eval.Java20BenchmarkRunner;
import io.github.moneymaker26754.agentforge.eval.MicroBenchmarkRunner;
import io.github.moneymaker26754.agentforge.eval.ReadmeMetricsRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "eval", description = "Run reproducible evaluation suites",
        subcommands = {EvalCommand.Run.class, EvalCommand.RenderReadme.class})
public final class EvalCommand implements Runnable {
    @Override public void run() { CommandLine.usage(this, System.out); }

    @Command(name = "run", description = "Run micro or Java20 evaluation")
    public static final class Run implements Callable<Integer> {
        private final ObjectMapper mapper;
        @Option(names = "--suite", required = true) private String suite;
        @Option(names = "--profile", defaultValue = "full") private String profile;
        @Option(names = "--output", defaultValue = "build/reports/agentforge") private Path output;
        @Spec private CommandLine.Model.CommandSpec spec;

        public Run(ObjectMapper mapper) { this.mapper = mapper; }

        @Override public Integer call() {
            BenchmarkReport report;
            if ("micro".equalsIgnoreCase(suite)) {
                report = new MicroBenchmarkRunner().run(profile);
            } else if ("java20".equalsIgnoreCase(suite)) {
                report = new Java20BenchmarkRunner().environmentFailed(profile,
                        "SWE-bench harness was not executed by this lightweight command; see docs/evaluation.md");
            } else {
                throw new CommandLine.ParameterException(spec.commandLine(), "suite must be micro or java20");
            }
            new BenchmarkReportWriter(mapper).write(report, output);
            spec.commandLine().getOut().printf("suite=%s profile=%s passed=%d/%d environment_failed=%d output=%s%n",
                    report.suite(), report.profile(), report.metrics().passed(), report.metrics().total(),
                    report.metrics().environmentFailed(), output.toAbsolutePath());
            return report.metrics().failed() == 0 ? 0 : 2;
        }
    }

    @Command(name = "render-readme", description = "Regenerate README metrics from a report")
    public static final class RenderReadme implements Callable<Integer> {
        private final ObjectMapper mapper;
        @Option(names = "--report", defaultValue = "build/reports/agentforge/micro-full-results.json") private Path report;
        @Option(names = "--readme", defaultValue = "README.md") private Path readme;

        public RenderReadme(ObjectMapper mapper) { this.mapper = mapper; }

        @Override public Integer call() throws Exception {
            BenchmarkReport parsed = mapper.readValue(report.toFile(), BenchmarkReport.class);
            String rendered = new ReadmeMetricsRenderer().render(Files.readString(readme), parsed);
            Files.writeString(readme, rendered);
            return 0;
        }
    }
}
