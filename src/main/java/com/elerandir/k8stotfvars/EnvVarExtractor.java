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

    /** A parsed {@code configMapKeyRef}/{@code secretKeyRef}: which resource, which key. */
    private record Ref(String name, String key, boolean optional) {
        static Ref from(MappingNode node) {
            return new Ref(
                    NodeYaml.scalar(NodeYaml.get(node, K8s.NAME)),
                    NodeYaml.scalar(NodeYaml.get(node, K8s.KEY)),
                    NodeYaml.isTrue(NodeYaml.get(node, K8s.OPTIONAL)));
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
            for (Node container : containers(podSpec)) {
                extractContainer(NodeYaml.asMapping(container), workloadLabel(resource), merged);
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
        String containerName = NodeYaml.scalar(NodeYaml.get(container, K8s.NAME));
        if (options.containerFilter() != null && !options.containerFilter().equals(containerName)) {
            return;
        }
        String origin = workloadLabel + " (container " + containerName + ")";

        // envFrom imports an entire ConfigMap/Secret. By design we include only
        // entries that are explicitly referenced, so envFrom imports are skipped.
        for (Node entry : NodeYaml.sequence(NodeYaml.get(container, K8s.ENV_FROM))) {
            skipEnvFrom(NodeYaml.asMapping(entry));
        }
        for (Node entry : NodeYaml.sequence(NodeYaml.get(container, K8s.ENV))) {
            extractEnv(NodeYaml.asMapping(entry), origin, merged);
        }
    }

    private void skipEnvFrom(MappingNode entry) {
        if (entry == null) {
            return;
        }
        warnSkippedEnvFrom(entry, K8s.CONFIG_MAP_REF, "ConfigMap", "configMapKeyRef");
        warnSkippedEnvFrom(entry, K8s.SECRET_REF, "Secret", "secretKeyRef");
    }

    private void warnSkippedEnvFrom(MappingNode entry, String refField, String kind, String explicitForm) {
        MappingNode ref = NodeYaml.getMapping(entry, refField);
        if (ref != null) {
            warn.accept("Skipping envFrom import of " + kind + " '"
                    + NodeYaml.scalar(NodeYaml.get(ref, K8s.NAME))
                    + "'; only explicitly referenced (" + explicitForm + ") entries are included.");
        }
    }

    private void extractEnv(MappingNode entry, String origin, Map<String, EnvVar> merged) {
        if (entry == null) {
            return;
        }
        String name = NodeYaml.scalar(NodeYaml.get(entry, K8s.NAME));
        if (name == null) {
            return;
        }
        Comment comment = NodeYaml.commentForMappingEntry(entry, K8s.NAME);

        if (NodeYaml.hasKey(entry, K8s.VALUE)) {
            put(merged, EnvVar.literal(name, NodeYaml.scalar(NodeYaml.get(entry, K8s.VALUE)), origin, comment));
            return;
        }

        MappingNode valueFrom = NodeYaml.getMapping(entry, K8s.VALUE_FROM);
        if (valueFrom == null) {
            // An env entry with neither value nor valueFrom resolves to "" in Kubernetes.
            put(merged, EnvVar.literal(name, "", origin, comment));
            return;
        }

        MappingNode configMapKeyRef = NodeYaml.getMapping(valueFrom, K8s.CONFIG_MAP_KEY_REF);
        if (configMapKeyRef != null) {
            resolveConfigMapKeyRef(name, Ref.from(configMapKeyRef), comment, merged);
            return;
        }
        MappingNode secretKeyRef = NodeYaml.getMapping(valueFrom, K8s.SECRET_KEY_REF);
        if (secretKeyRef != null) {
            resolveSecretKeyRef(name, Ref.from(secretKeyRef), comment, merged);
            return;
        }
        if (NodeYaml.hasKey(valueFrom, K8s.FIELD_REF)) {
            putUnresolved(name, EnvVar.Source.FIELD_REF, "fieldRef (resolved at pod runtime)", comment, merged);
        } else if (NodeYaml.hasKey(valueFrom, K8s.RESOURCE_FIELD_REF)) {
            putUnresolved(name, EnvVar.Source.RESOURCE_FIELD_REF, "resourceFieldRef (resolved at pod runtime)",
                    comment, merged);
        }
    }

    private void resolveConfigMapKeyRef(String name, Ref ref, Comment entryComment, Map<String, EnvVar> merged) {
        Optional<String> value = registry.configMapValue(ref.name(), ref.key());
        String origin = "configMap/" + ref.name() + ":" + ref.key();
        if (value.isPresent()) {
            Comment comment = entryComment.orElse(registry.configMapComment(ref.name(), ref.key()));
            put(merged, EnvVar.configMap(name, value.get(), origin, comment));
        } else {
            warn.accept("Env var '" + name + "' references ConfigMap '" + ref.name() + "' key '" + ref.key()
                    + "' which was not found in the input" + (ref.optional() ? " (marked optional)." : "."));
            if (options.includeUnresolved()) {
                put(merged, EnvVar.configMap(name, null, origin + " (unresolved)", entryComment));
            }
        }
    }

    private void resolveSecretKeyRef(String name, Ref ref, Comment entryComment, Map<String, EnvVar> merged) {
        // Secret values are intentionally not resolved; only the key is recorded.
        Comment comment = entryComment.orElse(registry.secretComment(ref.name(), ref.key()));
        put(merged, EnvVar.secret(name, ref.key(), "secret/" + ref.name() + ":" + ref.key(), comment));
    }

    private void putUnresolved(String name, EnvVar.Source source, String origin, Comment comment,
                               Map<String, EnvVar> merged) {
        warn.accept("Env var '" + name + "' uses " + origin + " and cannot be resolved statically.");
        if (options.includeUnresolved()) {
            put(merged, EnvVar.unresolved(name, source, origin, comment));
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

    private List<Node> containers(MappingNode podSpec) {
        List<Node> all = new ArrayList<>(NodeYaml.sequence(NodeYaml.get(podSpec, K8s.CONTAINERS)));
        if (options.includeInitContainers()) {
            all.addAll(NodeYaml.sequence(NodeYaml.get(podSpec, K8s.INIT_CONTAINERS)));
        }
        return all;
    }

    private static String workloadLabel(K8sResource resource) {
        String kind = resource.kind() != null ? resource.kind() : "workload";
        String name = resource.name() != null ? resource.name() : "?";
        return kind + "/" + name;
    }

    /** Locate the {@code PodSpec} for a workload resource, or {@code null} if it is not a workload. */
    private static MappingNode podSpecOf(K8sResource resource) {
        if (resource.hasKind(K8s.KIND_POD)) {
            return NodeYaml.getMapping(resource.node(), K8s.SPEC);
        }
        if (resource.hasKind(K8s.KIND_DEPLOYMENT, K8s.KIND_STATEFUL_SET, K8s.KIND_DAEMON_SET,
                K8s.KIND_REPLICA_SET, K8s.KIND_REPLICATION_CONTROLLER, K8s.KIND_JOB)) {
            return NodeYaml.digMapping(resource.node(), K8s.SPEC, K8s.TEMPLATE, K8s.SPEC);
        }
        if (resource.hasKind(K8s.KIND_CRON_JOB)) {
            return NodeYaml.digMapping(resource.node(), K8s.SPEC, K8s.JOB_TEMPLATE, K8s.SPEC, K8s.TEMPLATE, K8s.SPEC);
        }
        return null;
    }
}
