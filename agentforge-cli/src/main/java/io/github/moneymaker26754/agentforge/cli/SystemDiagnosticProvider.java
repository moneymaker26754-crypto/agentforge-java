package io.github.moneymaker26754.agentforge.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SystemDiagnosticProvider implements DiagnosticProvider {
    @Override public List<Diagnostic> diagnose() {
        var result = new ArrayList<Diagnostic>();
        int feature = Runtime.version().feature();
        result.add(new Diagnostic("java", feature >= 21, "Java " + feature));
        result.add(process("docker", List.of("docker", "info", "--format", "{{.ServerVersion}}"), true));
        result.add(process("ollama", List.of("ollama", "list"), false));
        return List.copyOf(result);
    }

    private Diagnostic process(String name, List<String> argv, boolean required) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            boolean available = finished && process.exitValue() == 0;
            String detail = available ? "available" : (required ? "daemon unavailable" : "optional provider unavailable");
            return new Diagnostic(name, available, detail);
        } catch (Exception exception) {
            return new Diagnostic(name, false, exception.getMessage());
        }
    }
}

