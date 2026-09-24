package io.github.moneymaker26754.agentforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class DoctorCommandTest {

    @Test
    void reportsEachDependencyAndReturnsFailureWhenRequiredDependencyIsMissing() {
        var output = new StringWriter();
        var command = new DoctorCommand(() -> List.of(
                new Diagnostic("java", true, "Java 21"),
                new Diagnostic("docker", false, "daemon unavailable"),
                new Diagnostic("ollama", false, "optional provider unavailable")));
        var line = new CommandLine(command).setOut(new PrintWriter(output));

        int exit = line.execute();

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString()).contains("PASS java", "FAIL docker", "WARN ollama");
    }
}

