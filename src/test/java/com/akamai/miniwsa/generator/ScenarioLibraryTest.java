package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.generator.ScenarioLibrary.UnknownScenarioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioLibraryTest {

    private static final int MAX_COUNT = 10000;

    @Test
    void loads_bundled_scenarios_at_startup() {
        ScenarioLibrary lib = new ScenarioLibrary(
                new DefaultResourceLoader(),
                "classpath:generator-scenarios.yml",
                MAX_COUNT);
        lib.load();

        assertThat(lib.all()).extracting(Scenario::name)
                .contains("quiet-bot-day", "single-targeted-attack", "multi-vector-incident", "slow-burn");
    }

    @Test
    void lookup_by_name_returns_scenario(@TempDir Path tmp) throws IOException {
        ScenarioLibrary lib = libraryWith(tmp, """
                scenarios:
                  - name: t1
                    description: test
                    count: 100
                    waves: []
                """);

        Scenario s = lib.findByName("t1");
        assertThat(s.count()).isEqualTo(100);
        assertThat(s.description()).isEqualTo("test");
    }

    @Test
    void lookup_by_unknown_name_throws(@TempDir Path tmp) throws IOException {
        ScenarioLibrary lib = libraryWith(tmp, """
                scenarios:
                  - name: t1
                    description: test
                    count: 100
                """);

        assertThatThrownBy(() -> lib.findByName("does-not-exist"))
                .isInstanceOf(UnknownScenarioException.class)
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("t1");
    }

    @Test
    void rejects_scenario_with_duplicate_name(@TempDir Path tmp) {
        assertThatThrownBy(() -> libraryWith(tmp, """
                scenarios:
                  - name: dup
                    description: a
                    count: 100
                  - name: dup
                    description: b
                    count: 100
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate scenario name");
    }

    @Test
    void rejects_scenario_with_wave_sum_exceeding_count(@TempDir Path tmp) {
        assertThatThrownBy(() -> libraryWith(tmp, """
                scenarios:
                  - name: bad
                    description: bad
                    count: 10
                    waves:
                      - size: 20
                        durationSeconds: 30
                        category: INJECTION
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sum of wave sizes");
    }

    @Test
    void rejects_scenario_with_count_exceeding_max(@TempDir Path tmp) {
        assertThatThrownBy(() -> libraryWith(tmp, """
                scenarios:
                  - name: huge
                    description: huge
                    count: 999999
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds max-count");
    }

    private static ScenarioLibrary libraryWith(Path tmp, String yamlContent) throws IOException {
        Path file = tmp.resolve("scenarios.yml");
        Files.writeString(file, yamlContent);
        ScenarioLibrary lib = new ScenarioLibrary(
                new DefaultResourceLoader(),
                "file:" + file.toAbsolutePath(),
                MAX_COUNT);
        lib.load();
        return lib;
    }
}
