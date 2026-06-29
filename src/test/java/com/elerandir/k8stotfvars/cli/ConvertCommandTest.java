package com.elerandir.k8stotfvars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Manifest file discovery")
class ConvertCommandTest {

    private static Path write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "kind: ConfigMap\n");
    }

    @Nested
    @DisplayName("when given a directory")
    class Directories {

        @Test
        @DisplayName("collects .yaml and .yml files recursively, sorted, skipping other files")
        void collectsManifestsRecursively(@TempDir Path dir) throws IOException {
            write(dir.resolve("b.yaml"));
            write(dir.resolve("a.yml"));
            write(dir.resolve("notes.txt"));
            write(dir.resolve("nested/c.yaml"));

            List<Path> found = ConvertCommand.collectManifestFiles(List.of(dir));

            assertEquals(
                    List.of(dir.resolve("a.yml"), dir.resolve("b.yaml"), dir.resolve("nested/c.yaml")),
                    found);
        }

        @Test
        @DisplayName("matches extensions case-insensitively")
        void matchesExtensionsCaseInsensitively(@TempDir Path dir) throws IOException {
            write(dir.resolve("UPPER.YAML"));

            assertEquals(List.of(dir.resolve("UPPER.YAML")), ConvertCommand.collectManifestFiles(List.of(dir)));
        }
    }

    @Nested
    @DisplayName("when given explicit files")
    class ExplicitFiles {

        @Test
        @DisplayName("keeps the given files")
        void keepsGivenFiles(@TempDir Path dir) throws IOException {
            Path a = write(dir.resolve("a.yaml"));
            Path b = write(dir.resolve("b.yaml"));

            assertEquals(List.of(a, b), ConvertCommand.collectManifestFiles(List.of(a, b)));
        }

        @Test
        @DisplayName("deduplicates a path passed more than once")
        void deduplicatesRepeatedPaths(@TempDir Path dir) throws IOException {
            Path a = write(dir.resolve("a.yaml"));

            assertEquals(List.of(a), ConvertCommand.collectManifestFiles(List.of(a, a)));
        }
    }

    @Test
    @DisplayName("fails when a path does not exist")
    void failsForMissingPath(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yaml");
        assertThrows(NoSuchFileException.class, () -> ConvertCommand.collectManifestFiles(List.of(missing)));
    }

    @Test
    @DisplayName("reports no files for an empty directory")
    void emptyDirectoryYieldsNoFiles(@TempDir Path dir) throws IOException {
        assertTrue(ConvertCommand.collectManifestFiles(List.of(dir)).isEmpty());
    }
}
