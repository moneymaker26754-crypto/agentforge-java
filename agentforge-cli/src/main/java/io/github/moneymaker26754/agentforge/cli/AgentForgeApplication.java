package io.github.moneymaker26754.agentforge.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;
import picocli.spring.PicocliSpringFactory;

@SpringBootApplication(scanBasePackages = "io.github.moneymaker26754.agentforge")
public class AgentForgeApplication {
    public static void main(String[] args) {
        int exitCode;
        try (var context = new SpringApplicationBuilder(AgentForgeApplication.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run()) {
            exitCode = new CommandLine(new RootCommand(), new PicocliSpringFactory(context)).execute(args);
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}

