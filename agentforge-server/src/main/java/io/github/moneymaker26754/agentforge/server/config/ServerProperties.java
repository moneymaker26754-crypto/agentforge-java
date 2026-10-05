package io.github.moneymaker26754.agentforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Control-plane settings, bound from the {@code agentforge.server.*} environment namespace. */
@ConfigurationProperties(prefix = "agentforge.server")
public record ServerProperties(String reposRoot, int maxConcurrentTasks, String provider, String sandboxMode,
        String githubBaseUrl, String webhookSecret, boolean networkAllowed) {
    public ServerProperties {
        reposRoot = reposRoot == null ? "" : reposRoot;
        if (maxConcurrentTasks <= 0) {
            throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        }
        provider = provider == null || provider.isBlank() ? "deepseek" : provider;
        sandboxMode = sandboxMode == null || sandboxMode.isBlank() ? "docker" : sandboxMode;
        githubBaseUrl = githubBaseUrl == null || githubBaseUrl.isBlank() ? "https://api.github.com" : githubBaseUrl;
        webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }
}
