package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.K8sResource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Indexes ConfigMap and Secret resources by name so that env var references can
 * be resolved to concrete key/value data.
 */
public final class ResourceRegistry {

    private final Map<String, Map<String, String>> configMaps = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> secrets = new LinkedHashMap<>();

    private ResourceRegistry() {
    }

    /**
     * Build a registry from parsed resources.
     *
     * @param resources all parsed resources
     * @param warn      sink for non-fatal diagnostics (e.g. undecodable secret data)
     */
    public static ResourceRegistry from(List<K8sResource> resources, Consumer<String> warn) {
        ResourceRegistry registry = new ResourceRegistry();
        for (K8sResource resource : resources) {
            if (resource.name() == null) {
                continue;
            }
            if (resource.hasKind("ConfigMap")) {
                registry.configMaps.put(resource.name(), configMapData(resource));
            } else if (resource.hasKind("Secret")) {
                registry.secrets.put(resource.name(), secretData(resource, warn));
            }
        }
        return registry;
    }

    /** Look up a single key in a named ConfigMap. */
    public Optional<String> configMapValue(String name, String key) {
        return lookup(configMaps, name, key);
    }

    /** Look up a single key in a named Secret. */
    public Optional<String> secretValue(String name, String key) {
        return lookup(secrets, name, key);
    }

    /** All key/value pairs of a named ConfigMap, in declaration order. */
    public Optional<Map<String, String>> configMap(String name) {
        return Optional.ofNullable(configMaps.get(name));
    }

    /** All key/value pairs of a named Secret, in declaration order. */
    public Optional<Map<String, String>> secret(String name) {
        return Optional.ofNullable(secrets.get(name));
    }

    private static Optional<String> lookup(Map<String, Map<String, String>> index, String name, String key) {
        Map<String, String> data = index.get(name);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(data.get(key));
    }

    private static Map<String, String> configMapData(K8sResource resource) {
        Map<String, String> data = new LinkedHashMap<>();
        Map<String, Object> raw = Yaml.digMap(resource.raw(), "data");
        if (raw != null) {
            raw.forEach((k, v) -> data.put(k, Yaml.scalar(v)));
        }
        return data;
    }

    private static Map<String, String> secretData(K8sResource resource, Consumer<String> warn) {
        Map<String, String> data = new LinkedHashMap<>();
        // base64-encoded `data`
        Map<String, Object> encoded = Yaml.digMap(resource.raw(), "data");
        if (encoded != null) {
            encoded.forEach((k, v) -> data.put(k, decodeBase64(Yaml.scalar(v), resource.name(), k, warn)));
        }
        // plaintext `stringData` takes precedence over `data` for the same key,
        // matching Kubernetes semantics.
        Map<String, Object> plain = Yaml.digMap(resource.raw(), "stringData");
        if (plain != null) {
            plain.forEach((k, v) -> data.put(k, Yaml.scalar(v)));
        }
        return data;
    }

    private static String decodeBase64(String value, String secretName, String key, Consumer<String> warn) {
        if (value == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            warn.accept("Secret '" + secretName + "' key '" + key
                    + "' is not valid base64; using the raw value verbatim.");
            return value;
        }
    }
}
