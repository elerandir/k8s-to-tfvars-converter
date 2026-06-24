package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.K8sResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses Kubernetes YAML manifests (including multi-document files) into a flat
 * list of {@link K8sResource}.
 */
public final class ManifestParser {

    private ManifestParser() {
    }

    /** Parse every YAML document found in the given files. */
    public static List<K8sResource> parseFiles(List<Path> files) throws IOException {
        List<K8sResource> resources = new ArrayList<>();
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                resources.addAll(parse(reader, file.toString()));
            }
        }
        return resources;
    }

    /** Parse every YAML document available from the given reader. */
    public static List<K8sResource> parse(Reader reader, String sourceLabel) {
        Yaml yaml = newYaml();
        List<K8sResource> resources = new ArrayList<>();
        for (Object document : yaml.loadAll(reader)) {
            collect(document, sourceLabel, resources);
        }
        return resources;
    }

    /**
     * A document may itself be a {@code List} (kind) or a single resource. Lists
     * are flattened so that {@code kind: List} manifests are handled.
     */
    private static void collect(Object document, String sourceLabel, List<K8sResource> out) {
        Map<String, Object> map = com.elerandir.k8stotfvars.Yaml.asMap(document);
        if (map == null) {
            return; // empty document (e.g. trailing "---") or non-mapping scalar
        }
        String kind = com.elerandir.k8stotfvars.Yaml.scalar(map.get("kind"));
        if ("List".equals(kind)) {
            for (Object item : com.elerandir.k8stotfvars.Yaml.asList(map.get("items"))) {
                collect(item, sourceLabel, out);
            }
            return;
        }
        String name = com.elerandir.k8stotfvars.Yaml.scalar(
                com.elerandir.k8stotfvars.Yaml.dig(map, "metadata", "name"));
        out.add(new K8sResource(kind, name, map));
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        // SafeConstructor rejects arbitrary Java type tags, so untrusted manifests
        // cannot trigger deserialization of unexpected classes.
        return new Yaml(new SafeConstructor(options));
    }
}
