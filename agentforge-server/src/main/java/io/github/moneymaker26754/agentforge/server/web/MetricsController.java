package io.github.moneymaker26754.agentforge.server.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime metrics endpoint: task throughput, session accounting and pending approvals. */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {
    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> metrics(@RequestParam(defaultValue = "100") int taskLimit) {
        return metrics.summary(taskLimit);
    }
}
