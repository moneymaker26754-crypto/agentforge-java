package io.github.moneymaker26754.agentforge.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "AgentForge 0.1.0",
        description = "A checkpointed, policy-aware Java coding agent",
        subcommands = {DoctorCommand.class, RunCommand.class, ResumeCommand.class, SessionCommand.class,
                ReportCommand.class, EvalCommand.class})
public final class RootCommand implements Runnable {
    @Override public void run() { CommandLine.usage(this, System.out); }
}
