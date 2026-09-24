package io.github.moneymaker26754.agentforge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BenchmarkReportWriter {
    private final ObjectMapper mapper;

    public BenchmarkReportWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(BenchmarkReport report, Path directory) {
        try {
            Files.createDirectories(directory);
            String stem = report.suite() + "-" + report.profile();
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(stem + "-results.json").toFile(), report);
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(stem + "-manifest.json").toFile(), report.manifest());
            var csv = new StringBuilder("id,category,status,duration_nanos,input_tokens,output_tokens,cost_cny,failure_reason\n");
            for (BenchmarkCaseResult result : report.results()) {
                csv.append(escape(result.id())).append(',').append(escape(result.category())).append(',')
                        .append(result.status()).append(',').append(result.durationNanos()).append(',')
                        .append(result.inputTokens()).append(',').append(result.outputTokens()).append(',')
                        .append(result.costCny()).append(',').append(escape(result.failureReason())).append('\n');
            }
            Files.writeString(directory.resolve(stem + "-results.csv"), csv, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot write benchmark report", exception);
        }
    }

    private String escape(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return '"' + safe + '"';
    }
}
