package com.elerandir.k8stotfvars.model;

/**
 * A single environment variable extracted from a workload container.
 *
 * <p>Secret-sourced variables ({@link Source#SECRET}) are intentionally not
 * resolved to a value: only the {@link #secretKey} (the key within the Secret)
 * is recorded, for another part of the system to resolve later.
 *
 * @param name      the environment variable name (the output map key); kept verbatim
 * @param value     the resolved value for literal/ConfigMap vars, or {@code null}
 * @param secretKey for Secret-sourced vars, the key within the Secret; else {@code null}
 * @param source    where the value came from
 * @param origin    a human-readable description of the origin, used for diagnostics
 * @param comment   any manifest comment captured near this variable's definition
 */
public record EnvVar(String name, String value, String secretKey, Source source, String origin, Comment comment) {

    public enum Source {
        /** Inline {@code env[].value}. */
        LITERAL,
        /** Resolved from a ConfigMap (via {@code configMapKeyRef} or {@code envFrom.configMapRef}). */
        CONFIG_MAP,
        /** Sourced from a Secret (via {@code secretKeyRef} or {@code envFrom.secretRef}); value not resolved. */
        SECRET,
        /** {@code valueFrom.fieldRef} — only known at pod runtime, cannot be resolved statically. */
        FIELD_REF,
        /** {@code valueFrom.resourceFieldRef} — only known at pod runtime, cannot be resolved statically. */
        RESOURCE_FIELD_REF
    }

    public boolean isSecret() {
        return source == Source.SECRET;
    }

    /**
     * Whether this variable produces a usable output entry. Secrets count as
     * resolved (the key is recorded by design); field refs and missing ConfigMap
     * references do not.
     */
    public boolean resolved() {
        return switch (source) {
            case SECRET -> true;
            case LITERAL, CONFIG_MAP -> value != null;
            case FIELD_REF, RESOURCE_FIELD_REF -> false;
        };
    }
}
