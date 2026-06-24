package com.elerandir.k8stotfvars.model;

import org.yaml.snakeyaml.nodes.MappingNode;

/**
 * A single Kubernetes resource parsed from a YAML document. The original
 * {@link MappingNode} is retained (rather than a plain {@code Map}) so that
 * comments attached to entries can be recovered.
 *
 * @param kind  the {@code kind} field (e.g. {@code Deployment}, {@code ConfigMap})
 * @param name  the {@code metadata.name} field, or {@code null} if absent
 * @param node  the document's root mapping node
 */
public record K8sResource(String kind, String name, MappingNode node) {

    public boolean hasKind(String... kinds) {
        if (kind == null) {
            return false;
        }
        for (String k : kinds) {
            if (kind.equals(k)) {
                return true;
            }
        }
        return false;
    }
}
