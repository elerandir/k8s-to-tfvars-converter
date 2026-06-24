package com.elerandir.k8stotfvars.model;

import java.util.Map;

/**
 * A single Kubernetes resource parsed from a YAML document, kept as the raw
 * deserialized map so that callers can navigate arbitrary fields without a full
 * typed model of the Kubernetes API.
 *
 * @param kind  the {@code kind} field (e.g. {@code Deployment}, {@code ConfigMap})
 * @param name  the {@code metadata.name} field, or {@code null} if absent
 * @param raw   the full deserialized document
 */
public record K8sResource(String kind, String name, Map<String, Object> raw) {

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
