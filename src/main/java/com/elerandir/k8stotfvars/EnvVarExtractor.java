package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.Comment;
import com.elerandir.k8stotfvars.model.EnvVar;
import com.elerandir.k8stotfvars.model.K8sResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Extracts environment variables from the containers of Kubernetes workload
 * resources, resolving ConfigMap references against a {@link ResourceRegistry}
 * and recording Secret references by key (without resolving their values).
 */
public final class EnvVarExtractor {

    /** Configuration for an extraction run. */
    public record Options(String containerFilter, boolean includeInitContainers, boolean includeUnresolved) {
        public static Options defaults() {
            return new Options(null, false, false);
        }
    }

    private final ResourceRegistry registry;
    private final Options options;
    private final Consumer<String> warn;

    public EnvVarExtractor(ResourceRegistry registry, Options options, Consumer<String> warn) {
        this.registry = registry;
        this.options = options;
        this.warn = warn;
    }

    /**
     * Extract env vars from all workload resources, merged into a single ordered
     * map. When the same name appears more than once, the later occurrence wins
     * (mirroring how a later {@code env} entry overrides an earlier one), and a
     * warning is emitted on a real change of value.
     */
    public List<EnvVar> extract(List<K8sResource> resources) {
        Map<String, EnvVar> merged = new LinkedHashMap<>();
        int workloads = 0;
        for (K8sResource resource : resources) {
            MappingNode podSpec = podSpecOf(resource);
            if (podSpec == null) {
                continue;
            }
            workloads++;
            String workloadLabel = (resource.kind() != null ? resource.kind() : "workload")
                    + "/" + (resource.name() != null ? resource.name() : "?");
            for (Node container : containers(podSpec)) {
                extractContainer(NodeYaml.asMapping(container), workloadLabel, merged);
            }
        }
        if (workloads == 0) {
            warn.accept("No workload resources (Deployment, StatefulSet, DaemonSet, Job, CronJob, Pod, ...) "
                    + "were found in the input.");
        }
        return new ArrayList<>(merged.values());
    }

    private void extractContainer(MappingNode container, String workloadLabel, Map<String, EnvVar> merged) {
        if (container == null) {
            return;
        }
        String containerName = NodeYaml.scalar(NodeYaml.get(container, "name"));
        if (options.containerFilter() != null && !options.containerFilter().equals(containerName)) {
            return;
        }
        String origin = workloadLabel + " (container " + containerName + ")";

        // Kubernetes applies envFrom first, then env (env overrides envFrom).
        for (Node entry : NodeYaml.sequence(NodeYaml.get(container, "envFrom"))) {
            extractEnvFrom(NodeYaml.asMapping(entry), merged);
        }
        for (Node entry : NodeYaml.sequence(NodeYaml.get(container, "env"))) {
            extractEnv(NodeYaml.asMapping(entry), origin, merged);
        }
    }

    private void extractEnvFrom(MappingNode entry, Map<String, EnvVar> merged) {
        if (entry == null) {
            return;
        }
        String prefix = Optional.ofNullable(NodeYaml.scalar(NodeYaml.get(entry, "prefix"))).orElse("");

        MappingNode configMapRef = NodeYaml.getMapping(entry, "configMapRef");
        if (configMapRef != null) {
            String name = NodeYaml.scalar(NodeYaml.get(configMapRef, "name"));
            boolean optional = NodeYaml.isTrue(NodeYaml.get(configMapRef, "optional"));
            Optional<Map<String, ResourceRegistry.ConfigEntry>> data = registry.configMap(name);
            if (data.isEmpty()) {
                warnMissing("ConfigMap", name, optional, "envFrom");
            } else {
                data.get().forEach((key, e) -> put(merged,
                        new EnvVar(prefix + key, e.value(), null, EnvVar.Source.CONFIG_MAP,
                                "configMap/" + name, e.comment())));
            }
        }

        MappingNode secretRef = NodeYaml.getMapping(entry, "secretRef");
        if (secretRef != null) {
            String name = NodeYaml.scalar(NodeYaml.get(secretRef, "name"));
            boolean optional = NodeYaml.isTrue(NodeYaml.get(secretRef, "optional"));
            Optional<Map<String, Comment>> data = registry.secret(name);
            if (data.isEmpty()) {
                warnMissing("Secret", name, optional, "envFrom");
            } else {
                data.get().forEach((key, comment) -> put(merged,
                        new EnvVar(prefix + key, null, key, EnvVar.Source.SECRET,
                                "secret/" + name, comment)));
            }
        }
    }

    private void extractEnv(MappingNode entry, String origin, Map<String, EnvVar> merged) {
        if (entry == null) {
            return;
        }
        String name = NodeYaml.scalar(NodeYaml.get(entry, "name"));
        if (name == null) {
            return;
        }
        Comment comment = NodeYaml.commentForMappingEntry(entry, "name");

        if (NodeYaml.hasKey(entry, "value")) {
            put(merged, new EnvVar(name, NodeYaml.scalar(NodeYaml.get(entry, "value")), null,
                    EnvVar.Source.LITERAL, origin, comment));
            return;
        }

        MappingNode valueFrom = NodeYaml.getMapping(entry, "valueFrom");
        if (valueFrom == null) {
            // An env entry with neither value nor valueFrom resolves to "" in Kubernetes.
            put(merged, new EnvVar(name, "", null, EnvVar.Source.LITERAL, origin, comment));
            return;
        }

        MappingNode configMapKeyRef = NodeYaml.getMapping(valueFrom, "configMapKeyRef");
        if (configMapKeyRef != null) {
            resolveConfigMapKeyRef(name, configMapKeyRef, origin, comment, merged);
            return;
        }
        MappingNode secretKeyRef = NodeYaml.getMapping(valueFrom, "secretKeyRef");
        if (secretKeyRef != null) {
            resolveSecretKeyRef(name, secretKeyRef, comment, merged);
            return;
        }
        if (NodeYaml.hasKey(valueFrom, "fieldRef")) {
            putUnresolved(name, EnvVar.Source.FIELD_REF, "fieldRef (resolved at pod runtime)", comment, merged);
        } else if (NodeYaml.hasKey(valueFrom, "resourceFieldRef")) {
            putUnresolved(name, EnvVar.Source.RESOURCE_FIELD_REF, "resourceFieldRef (resolved at pod runtime)",
                    comment, merged);
        }
    }

    private void resolveConfigMapKeyRef(String name, MappingNode ref, String origin, Comment entryComment,
                                        Map<String, EnvVar> merged) {
        String refName = NodeYaml.scalar(NodeYaml.get(ref, "name"));
        String key = NodeYaml.scalar(NodeYaml.get(ref, "key"));
        boolean optional = NodeYaml.isTrue(NodeYaml.get(ref, "optional"));
        Optional<String> value = registry.configMapValue(refName, key);
        if (value.isPresent()) {
            Comment comment = entryComment.orElse(registry.configMapComment(refName, key));
            put(merged, new EnvVar(name, value.get(), null, EnvVar.Source.CONFIG_MAP,
                    "configMap/" + refName + ":" + key, comment));
        } else {
            warn.accept("Env var '" + name + "' references ConfigMap '" + refName + "' key '" + key
                    + "' which was not found in the input" + (optional ? " (marked optional)." : "."));
            if (options.includeUnresolved()) {
                put(merged, new EnvVar(name, null, null, EnvVar.Source.CONFIG_MAP,
                        "configMap/" + refName + ":" + key + " (unresolved)", entryComment));
            }
        }
    }

    private void resolveSecretKeyRef(String name, MappingNode ref, Comment entryComment, Map<String, EnvVar> merged) {
        String refName = NodeYaml.scalar(NodeYaml.get(ref, "name"));
        String key = NodeYaml.scalar(NodeYaml.get(ref, "key"));
        // Secret values are intentionally not resolved; only the key is recorded.
        Comment comment = entryComment.orElse(registry.secretComment(refName, key));
        put(merged, new EnvVar(name, null, key, EnvVar.Source.SECRET,
                "secret/" + refName + ":" + key, comment));
    }

    private void putUnresolved(String name, EnvVar.Source source, String origin, Comment comment,
                               Map<String, EnvVar> merged) {
        warn.accept("Env var '" + name + "' uses " + origin + " and cannot be resolved statically.");
        if (options.includeUnresolved()) {
            put(merged, new EnvVar(name, null, null, source, origin, comment));
        }
    }

    private void put(Map<String, EnvVar> merged, EnvVar value) {
        EnvVar previous = merged.put(value.name(), value);
        if (previous != null && !sameValue(previous, value)) {
            warn.accept("Env var '" + value.name() + "' is defined more than once; using the value from "
                    + value.origin() + " (overriding " + previous.origin() + ").");
        }
    }

    private static boolean sameValue(EnvVar a, EnvVar b) {
        return a.source() == b.source()
                && Objects.equals(a.value(), b.value())
                && Objects.equals(a.secretKey(), b.secretKey());
    }

    private void warnMissing(String kind, String name, boolean optional, String via) {
        warn.accept(via + " references " + kind + " '" + name
                + "' which was not found in the input"
                + (optional ? " (marked optional); skipping." : "; skipping."));
    }

    private List<Node> containers(MappingNode podSpec) {
        List<Node> all = new ArrayList<>(NodeYaml.sequence(NodeYaml.get(podSpec, "containers")));
        if (options.includeInitContainers()) {
            all.addAll(NodeYaml.sequence(NodeYaml.get(podSpec, "initContainers")));
        }
        return all;
    }

    /** Locate the {@code PodSpec} for a workload resource, or {@code null} if it is not a workload. */
    private static MappingNode podSpecOf(K8sResource resource) {
        if (resource.hasKind("Pod")) {
            return NodeYaml.getMapping(resource.node(), "spec");
        }
        if (resource.hasKind("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet",
                "ReplicationController", "Job")) {
            return NodeYaml.digMapping(resource.node(), "spec", "template", "spec");
        }
        if (resource.hasKind("CronJob")) {
            return NodeYaml.digMapping(resource.node(), "spec", "jobTemplate", "spec", "template", "spec");
        }
        return null;
    }
}
