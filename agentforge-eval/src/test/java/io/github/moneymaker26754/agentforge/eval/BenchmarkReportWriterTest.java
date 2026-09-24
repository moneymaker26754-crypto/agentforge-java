package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void writesRawJsonCsvAndManifest() throws Exception {
        var writer = new BenchmarkReportWriter(new ObjectMapper());
        writer.write(new MicroBenchmarkRunner().run("full"), tempDir);

        assertThat(tempDir.resolve("micro-full-results.json")).exists();
        assertThat(tempDir.resolve("micro-full-results.csv")).exists();
        assertThat(tempDir.resolve("micro-full-manifest.json")).exists();
        assertThat(Files.readString(tempDir.resolve("micro-full-results.csv"))).contains("stream/sse-fragment-name");
    }
}
