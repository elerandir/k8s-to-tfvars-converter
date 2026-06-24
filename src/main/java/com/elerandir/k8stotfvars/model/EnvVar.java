package com.elerandir.k8stotfvars.model;

/**
 * A single environment variable extracted from a workload container.
 *
 * @param name     the environment variable name (becomes the tfvars key)
 * @param value    the resolved value, or {@code null} if it could not be resolved
 * @param source   where the value came from
 * @param origin   a human-readable description of the origin (container, ConfigMap, Secret, ...)
 *                 used for diagnostics
 */
public record EnvVar(String name, String value, Source source, String origin) {

    public enum Source {
        /** Inline {@code env[].value}. */
        LITERAL,
        /** Resolved from a ConfigMap (via {@code configMapKeyRef} or {@code envFrom.configMapRef}). */
        CONFIG_MAP,
        /** Resolved from a Secret (via {@code secretKeyRef} or {@code envFrom.secretRef}). */
        SECRET,
        /** {@code valueFrom.fieldRef} — only known at pod runtime, cannot be resolved statically. */
        FIELD_REF,
        /** {@code valueFrom.resourceFieldRef} — only known at pod runtime, cannot be resolved statically. */
        RESOURCE_FIELD_REF
    }

    public boolean resolved() {
        return value != null;
    }
}
