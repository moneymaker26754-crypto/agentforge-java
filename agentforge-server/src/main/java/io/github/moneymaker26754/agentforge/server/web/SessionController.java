package io.github.moneymaker26754.agentforge.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.SessionId;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Session inspection API: status, metrics, audit events and rendered reports. */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionReportService reports;
    private final ObjectMapper mapper;

    public SessionController(SessionReportService reports, ObjectMapper mapper) {
        this.reports = reports;
        this.mapper = mapper;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String sessionId) {
        SessionId id = new SessionId(sessionId);
        var snapshot = reports.snapshot(id);
        if (snapshot.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "status", snapshot.get().status().name(),
                "metrics", reports.metrics(id)));
    }

    @GetMapping("/{sessionId}/events")
    public ResponseEntity<Object> events(@PathVariable String sessionId) {
        SessionId id = new SessionId(sessionId);
        if (reports.snapshot(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(mapper.readTree(reports.redactedEventsJson(id)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot render events", exception);
        }
    }

    @GetMapping("/{sessionId}/report")
    public ResponseEntity<String> report(@PathVariable String sessionId,
            @RequestParam(defaultValue = "markdown") String format) {
        SessionId id = new SessionId(sessionId);
        if (reports.snapshot(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reports.report(id, format));
    }
}
