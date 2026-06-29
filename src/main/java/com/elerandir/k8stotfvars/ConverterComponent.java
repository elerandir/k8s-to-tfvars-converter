package com.elerandir.k8stotfvars;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Singleton;

/**
 * Dagger component that wires the conversion object graph. The runtime
 * {@link ConversionConfig} is bound in via the factory, producing a fully
 * assembled {@link Converter}.
 */
@Singleton
@Component
public interface ConverterComponent {

    Converter converter();

    @Component.Factory
    interface Factory {
        ConverterComponent create(@BindsInstance ConversionConfig config);
    }
}
