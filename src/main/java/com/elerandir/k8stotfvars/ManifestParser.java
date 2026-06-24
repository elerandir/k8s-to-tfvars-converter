package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.K8sResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Parses Kubernetes YAML manifests (including multi-document files) into a flat
 * list of {@link K8sResource}, preserving comments via SnakeYAML's node API.
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
        List<K8sResource> resources = new ArrayList<>();
        for (Node document : newYaml().composeAll(reader)) {
            collect(document, resources);
        }
        return resources;
    }

    /**
     * A document may itself be a {@code kind: List} wrapper; its items are
     * flattened so that list manifests are handled.
     */
    private static void collect(Node document, List<K8sResource> out) {
        MappingNode mapping = NodeYaml.asMapping(document);
        if (mapping == null) {
            return; // empty document (e.g. trailing "---") or non-mapping scalar
        }
        String kind = NodeYaml.scalar(NodeYaml.get(mapping, "kind"));
        if ("List".equals(kind)) {
            for (Node item : NodeYaml.sequence(NodeYaml.get(mapping, "items"))) {
                collect(item, out);
            }
            return;
        }
        String name = NodeYaml.scalar(NodeYaml.get(NodeYaml.getMapping(mapping, "metadata"), "name"));
        out.add(new K8sResource(kind, name, mapping));
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setProcessComments(true);
        return new Yaml(options);
    }
}
