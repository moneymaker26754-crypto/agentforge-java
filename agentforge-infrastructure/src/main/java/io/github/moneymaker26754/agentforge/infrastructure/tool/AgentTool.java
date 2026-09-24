package io.github.moneymaker26754.agentforge.infrastructure.tool;

import io.github.moneymaker26754.agentforge.core.RiskLevel;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface AgentTool {
    String name();

    String description();

    RiskLevel risk();

    boolean idempotent() default false;
}

