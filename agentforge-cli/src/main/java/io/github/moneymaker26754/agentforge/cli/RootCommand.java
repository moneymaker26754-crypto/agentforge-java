package io.github.moneymaker26754.agentforge.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "AgentForge 0.2.0",
        description = "A checkpointed, policy-aware Java coding agent with a CI diagnosis control plane",
        subcommands = {DoctorCommand.class, RunCommand.class, CiCommand.class, ResumeCommand.class, SessionCommand.class,
                ReportCommand.class, EvalCommand.class})
public final class RootCommand implements Runnable {
    @Override public void run() { CommandLine.usage(this, System.out); }
}
