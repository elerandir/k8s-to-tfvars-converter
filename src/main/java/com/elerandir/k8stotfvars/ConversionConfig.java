package com.elerandir.k8stotfvars;

/**
 * Runtime configuration for a conversion, supplied by the CLI and bound into the
 * Dagger graph so the wired {@link Converter} carries it.
 *
 * @param options extraction options (container filter, init containers, unresolved handling)
 * @param header  whether to emit the generated-file header comment
 */
public record ConversionConfig(EnvVarExtractor.Options options, boolean header) {
}
