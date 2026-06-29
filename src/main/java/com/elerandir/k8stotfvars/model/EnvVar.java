package com.elerandir.k8stotfvars.model;

/**
 * A single environment variable extracted from a workload container.
 *
 * <p>Secret-sourced variables ({@link Source#SECRET}) are intentionally not
 * resolved to a value: only the {@link #secretKey} (the key within the Secret)
 * is recorded, for another part of the system to resolve later.
 *
 * <p>Prefer the static factories ({@link #literal}, {@link #configMap},
 * {@link #secret}, {@link #unresolved}) over the canonical constructor: they
 * make the source explicit and avoid passing {@code null} for the unused field.
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

    /** A literal {@code env[].value} (or an env entry that resolves to the empty string). */
    public static EnvVar literal(String name, String value, String origin, Comment comment) {
        return new EnvVar(name, value, null, Source.LITERAL, origin, comment);
    }

    /** A variable resolved from a ConfigMap. A {@code null} value marks it unresolved. */
    public static EnvVar configMap(String name, String value, String origin, Comment comment) {
        return new EnvVar(name, value, null, Source.CONFIG_MAP, origin, comment);
    }

    /** A Secret-sourced variable, recording the Secret key rather than its value. */
    public static EnvVar secret(String name, String secretKey, String origin, Comment comment) {
        return new EnvVar(name, null, secretKey, Source.SECRET, origin, comment);
    }

    /** A variable that cannot be resolved statically (e.g. {@code fieldRef}). */
    public static EnvVar unresolved(String name, Source source, String origin, Comment comment) {
        return new EnvVar(name, null, null, source, origin, comment);
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
