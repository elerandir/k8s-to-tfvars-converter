package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.EnvVar;
import com.elerandir.k8stotfvars.model.K8sResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * End-to-end conversion: parse manifests, index ConfigMaps/Secrets, extract and
 * resolve env vars, and render the resulting {@code .tfvars} content.
 *
 * <p>Wired by Dagger from the injected parser, writer, node helper, and the
 * runtime {@link ConversionConfig}.
 */
@Singleton
public final class Converter {

    /** The outcome of a conversion. */
    public record Result(String tfvars, List<EnvVar> envVars, List<String> warnings) {
        public long unresolvedCount() {
            return envVars.stream().filter(e -> !e.resolved()).count();
        }
    }

    private final ManifestParser parser;
    private final TfvarsWriter writer;
    private final NodeYaml nodeYaml;
    private final ConversionConfig config;

    @Inject
    Converter(ManifestParser parser, TfvarsWriter writer, NodeYaml nodeYaml, ConversionConfig config) {
        this.parser = parser;
        this.writer = writer;
        this.nodeYaml = nodeYaml;
        this.config = config;
    }

    public Result convertFiles(List<Path> files) throws IOException {
        return convert(parser.parseFiles(files));
    }

    public Result convert(Reader reader) {
        return convert(parser.parse(reader));
    }

    public Result convert(List<K8sResource> resources) {
        List<String> warnings = new ArrayList<>();
        Consumer<String> warn = warnings::add;

        ResourceRegistry registry = ResourceRegistry.from(nodeYaml, resources);
        List<EnvVar> envVars = new EnvVarExtractor(nodeYaml, registry, config.options(), warn).extract(resources);
        String tfvars = writer.render(envVars, config.header());
        return new Result(tfvars, envVars, warnings);
    }
}
