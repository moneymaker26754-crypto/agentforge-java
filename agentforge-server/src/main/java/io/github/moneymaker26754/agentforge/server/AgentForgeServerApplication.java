package io.github.moneymaker26754.agentforge.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "io.github.moneymaker26754.agentforge")
@ConfigurationPropertiesScan
public class AgentForgeServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentForgeServerApplication.class, args);
    }
}
