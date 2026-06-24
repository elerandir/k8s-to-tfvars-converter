package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.EnvVar;
import com.elerandir.k8stotfvars.model.K8sResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * End-to-end conversion: parse manifests, index ConfigMaps/Secrets, extract and
 * resolve env vars, and render the resulting {@code .tfvars} content.
 */
public final class Converter {

    /** The outcome of a conversion. */
    public record Result(String tfvars, List<EnvVar> envVars, List<String> warnings) {
        public long unresolvedCount() {
            return envVars.stream().filter(e -> !e.resolved()).count();
        }
    }

    private final EnvVarExtractor.Options extractorOptions;
    private final boolean header;

    public Converter(EnvVarExtractor.Options extractorOptions, boolean header) {
        this.extractorOptions = extractorOptions;
        this.header = header;
    }

    public Result convertFiles(List<Path> files) throws IOException {
        return convert(ManifestParser.parseFiles(files));
    }

    public Result convert(Reader reader, String sourceLabel) {
        return convert(ManifestParser.parse(reader, sourceLabel));
    }

    public Result convert(List<K8sResource> resources) {
        List<String> warnings = new ArrayList<>();
        Consumer<String> warn = warnings::add;

        ResourceRegistry registry = ResourceRegistry.from(resources, warn);
        List<EnvVar> envVars = new EnvVarExtractor(registry, extractorOptions, warn).extract(resources);
        String tfvars = new TfvarsWriter(header).render(envVars);
        return new Result(tfvars, envVars, warnings);
    }
}
