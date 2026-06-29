package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.Comment;
import com.elerandir.k8stotfvars.model.K8sResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

/**
 * Indexes ConfigMap and Secret resources by name.
 *
 * <p>ConfigMap data is captured as resolvable key/value pairs. Secret data is
 * captured by <em>key only</em> — values are deliberately not resolved, since
 * they are looked up elsewhere. Per-key comments are retained for both.
 */
public final class ResourceRegistry {

    /** A ConfigMap entry: its resolved value plus any comment near it. */
    public record ConfigEntry(String value, Comment comment) {
    }

    private final Map<String, Map<String, ConfigEntry>> configMaps = new LinkedHashMap<>();
    private final Map<String, Map<String, Comment>> secrets = new LinkedHashMap<>();

    private ResourceRegistry() {
    }

    public static ResourceRegistry from(List<K8sResource> resources) {
        ResourceRegistry registry = new ResourceRegistry();
        for (K8sResource resource : resources) {
            if (resource.name() == null) {
                continue;
            }
            if (resource.hasKind(K8s.KIND_CONFIG_MAP)) {
                registry.configMaps.put(resource.name(), configMapData(resource));
            } else if (resource.hasKind(K8s.KIND_SECRET)) {
                registry.secrets.put(resource.name(), secretKeys(resource));
            }
        }
        return registry;
    }

    public Optional<String> configMapValue(String name, String key) {
        return configMapEntry(name, key).map(ConfigEntry::value);
    }

    public Comment configMapComment(String name, String key) {
        return configMapEntry(name, key).map(ConfigEntry::comment).orElse(Comment.NONE);
    }

    public Comment secretComment(String name, String key) {
        Map<String, Comment> data = secrets.get(name);
        return data == null ? Comment.NONE : data.getOrDefault(key, Comment.NONE);
    }

    private Optional<ConfigEntry> configMapEntry(String name, String key) {
        Map<String, ConfigEntry> data = configMaps.get(name);
        return data == null ? Optional.empty() : Optional.ofNullable(data.get(key));
    }

    private static Map<String, ConfigEntry> configMapData(K8sResource resource) {
        Map<String, ConfigEntry> data = new LinkedHashMap<>();
        MappingNode dataNode = NodeYaml.getMapping(resource.node(), K8s.DATA);
        if (dataNode != null) {
            for (NodeTuple tuple : dataNode.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    data.put(key.getValue(),
                            new ConfigEntry(NodeYaml.scalar(tuple.getValueNode()),
                                    NodeYaml.commentForTuple(tuple)));
                }
            }
        }
        return data;
    }

    private static Map<String, Comment> secretKeys(K8sResource resource) {
        Map<String, Comment> keys = new LinkedHashMap<>();
        // Both `data` and `stringData` contribute key names; values are ignored.
        collectKeys(NodeYaml.getMapping(resource.node(), K8s.DATA), keys);
        collectKeys(NodeYaml.getMapping(resource.node(), K8s.STRING_DATA), keys);
        return keys;
    }

    private static void collectKeys(MappingNode mapping, Map<String, Comment> out) {
        if (mapping == null) {
            return;
        }
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key) {
                out.put(key.getValue(), NodeYaml.commentForTuple(tuple));
            }
        }
    }
}
