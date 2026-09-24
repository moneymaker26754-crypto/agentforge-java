package io.github.moneymaker26754.agentforge.infrastructure.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    String description();

    boolean required() default false;

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;
}

