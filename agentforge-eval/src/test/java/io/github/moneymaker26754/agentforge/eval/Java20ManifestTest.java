package io.github.moneymaker26754.agentforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Java20ManifestTest {
    @Test
    void pinsOfficialRevisionAndLexicographicFirstTwentyJavaInstances() {
        BenchmarkManifest manifest = Java20Manifest.manifest();

        assertThat(manifest.instanceIds()).hasSize(20).isSorted().doesNotHaveDuplicates();
        assertThat(manifest.instanceIds()).first().isEqualTo("apache__druid-13704");
        assertThat(manifest.instanceIds()).last().isEqualTo("google__gson-2134");
        assertThat(manifest.datasetRevision()).hasSize(40);
        assertThat(manifest.costCapCny()).isEqualTo(50.0);
    }
}
