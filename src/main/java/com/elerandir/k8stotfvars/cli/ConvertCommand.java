package com.elerandir.k8stotfvars.cli;

import com.elerandir.k8stotfvars.ConversionConfig;
import com.elerandir.k8stotfvars.Converter;
import com.elerandir.k8stotfvars.DaggerConverterComponent;
import com.elerandir.k8stotfvars.EnvVarExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI entry point: reads one or more Kubernetes manifest files (or directories
 * of manifests) and writes a Terraform {@code .tfvars} file.
 */
@Command(
        name = "k8s-to-tfvars",
        mixinStandardHelpOptions = true,
        version = "k8s-to-tfvars 0.1.0",
        description = "Extract container environment variables from Kubernetes manifests "
                + "into a Terraform .tfvars file with two maps: env_vars (literal and "
                + "ConfigMap-resolved values) and secrets (Secret keys, values not resolved). "
                + "Only explicitly referenced entries are included.")
public final class ConvertCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "PATH",
            description = "Manifest files or directories to read. Directories are searched "
                    + "recursively for .yaml and .yml files.")
    List<Path> inputs = new ArrayList<>();

    @Option(names = {"-o", "--output"},
            description = "Write tfvars to this file instead of standard output.")
    Path output;

    @Option(names = {"-c", "--container"},
            description = "Only extract from the container with this name.")
    String container;

    @Option(names = "--include-init-containers",
            description = "Also extract env vars from initContainers.")
    boolean includeInitContainers;

    @Option(names = "--include-unresolved",
            description = "Emit unresolved env vars (fieldRef, missing references) as commented "
                    + "placeholders instead of dropping them.")
    boolean includeUnresolved;

    @Option(names = "--fail-on-unresolved",
            description = "Exit with a non-zero status if any env var cannot be resolved.")
    boolean failOnUnresolved;

    @Option(names = "--header",
            negatable = true,
            description = "Include the generated-file header comment (default: true). "
                    + "Use --no-header to omit it.")
    boolean header = true;

    @Override
    public Integer call() throws IOException {
        List<Path> files = collectManifestFiles(inputs);
        if (files.isEmpty()) {
            System.err.println("No .yaml or .yml manifest files found in the given paths.");
            return 2;
        }

        ConversionConfig config = new ConversionConfig(
                new EnvVarExtractor.Options(container, includeInitContainers, includeUnresolved), header);
        Converter converter = DaggerConverterComponent.factory().create(config).converter();
        Converter.Result result = converter.convertFiles(files);

        for (String warning : result.warnings()) {
            System.err.println("warning: " + warning);
        }

        if (output != null) {
            Files.writeString(output, result.tfvars(), StandardCharsets.UTF_8);
            System.err.println("Wrote " + result.envVars().size() + " variable(s) to " + output);
        } else {
            System.out.print(result.tfvars());
        }

        if (failOnUnresolved && result.unresolvedCount() > 0) {
            System.err.println("error: " + result.unresolvedCount()
                    + " env var(s) could not be resolved (--fail-on-unresolved).");
            return 1;
        }
        return 0;
    }

    /** Expand the input paths into a deduplicated list of manifest files. */
    static List<Path> collectManifestFiles(List<Path> inputs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> walk = Files.walk(input)) {
                    walk.filter(Files::isRegularFile)
                            .filter(ConvertCommand::isManifest)
                            .sorted()
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(input)) {
                files.add(input);
            } else {
                throw new NoSuchFileException(input.toString());
            }
        }
        return files.stream().distinct().toList();
    }

    private static boolean isManifest(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
