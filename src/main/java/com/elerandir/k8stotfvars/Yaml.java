package com.elerandir.k8stotfvars;

import java.util.List;
import java.util.Map;

/**
 * Small helpers for safely navigating the untyped {@code Map}/{@code List}
 * structures that SnakeYAML produces.
 */
final class Yaml {

    private Yaml() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    /** Navigate a chain of map keys, returning {@code null} if any link is missing. */
    static Object dig(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            Map<String, Object> map = asMap(current);
            if (map == null) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    static Map<String, Object> digMap(Object root, String... path) {
        return asMap(dig(root, path));
    }

    static List<Object> digList(Object root, String... path) {
        return asList(dig(root, path));
    }

    /**
     * Render a scalar value as a string. Kubernetes string fields are usually
     * already strings, but YAML may parse unquoted values as numbers or booleans;
     * those are rendered with their natural string form.
     */
    static String scalar(Object o) {
        if (o == null) {
            return null;
        }
        return String.valueOf(o);
    }

    static boolean isTrue(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(o);
    }
}
