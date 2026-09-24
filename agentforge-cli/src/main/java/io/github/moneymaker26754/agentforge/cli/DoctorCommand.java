package io.github.moneymaker26754.agentforge.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "doctor", description = "Check Java, Docker, Ollama and DeepSeek configuration")
public final class DoctorCommand implements Callable<Integer> {
    private final DiagnosticProvider diagnostics;

    @Spec
    private CommandSpec spec;

    public DoctorCommand(DiagnosticProvider diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public Integer call() {
        boolean requiredFailure = false;
        for (Diagnostic diagnostic : diagnostics.diagnose()) {
            String status;
            if (diagnostic.available()) status = "PASS";
            else if (diagnostic.required()) {
                status = "FAIL";
                requiredFailure = true;
            } else status = "WARN";
            spec.commandLine().getOut().printf("%s %s - %s%n", status, diagnostic.name(), diagnostic.detail());
        }
        return requiredFailure ? 2 : 0;
    }
}

