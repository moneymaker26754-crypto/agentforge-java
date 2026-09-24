package io.github.moneymaker26754.agentforge.cli;

import java.util.List;

@FunctionalInterface
public interface DiagnosticProvider {
    List<Diagnostic> diagnose();
}

