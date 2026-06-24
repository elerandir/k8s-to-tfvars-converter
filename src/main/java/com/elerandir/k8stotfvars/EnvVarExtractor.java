package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.EnvVar;
import com.elerandir.k8stotfvars.model.K8sResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Extracts environment variables from the containers of Kubernetes workload
 * resources, resolving ConfigMap and Secret references against a
 * {@link ResourceRegistry}.
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
     * warning is emitted.
     */
    public List<EnvVar> extract(List<K8sResource> resources) {
        Map<String, EnvVar> merged = new LinkedHashMap<>();
        int workloads = 0;
        for (K8sResource resource : resources) {
            Map<String, Object> podSpec = podSpecOf(resource);
            if (podSpec == null) {
                continue;
            }
            workloads++;
            String workloadLabel = (resource.kind() != null ? resource.kind() : "workload")
                    + "/" + (resource.name() != null ? resource.name() : "?");
            for (Map<String, Object> container : containers(podSpec)) {
                extractContainer(container, workloadLabel, merged);
            }
        }
        if (workloads == 0) {
            warn.accept("No workload resources (Deployment, StatefulSet, DaemonSet, Job, CronJob, Pod, ...) "
                    + "were found in the input.");
        }
        return new ArrayList<>(merged.values());
    }

    private void extractContainer(Map<String, Object> container, String workloadLabel, Map<String, EnvVar> merged) {
        String containerName = Yaml.scalar(container.get("name"));
        if (options.containerFilter() != null && !options.containerFilter().equals(containerName)) {
            return;
        }
        String origin = workloadLabel + " (container " + containerName + ")";

        // Kubernetes applies envFrom first, then env (env overrides envFrom).
        for (Object entry : Yaml.asList(container.get("envFrom"))) {
            extractEnvFrom(Yaml.asMap(entry), merged);
        }
        for (Object entry : Yaml.asList(container.get("env"))) {
            extractEnv(Yaml.asMap(entry), origin, merged);
        }
    }

    private void extractEnvFrom(Map<String, Object> entry, Map<String, EnvVar> merged) {
        if (entry == null) {
            return;
        }
        String prefix = Optional.ofNullable(Yaml.scalar(entry.get("prefix"))).orElse("");

        Map<String, Object> configMapRef = Yaml.asMap(entry.get("configMapRef"));
        if (configMapRef != null) {
            String name = Yaml.scalar(configMapRef.get("name"));
            boolean optional = Yaml.isTrue(configMapRef.get("optional"));
            Optional<Map<String, String>> data = registry.configMap(name);
            if (data.isEmpty()) {
                warnMissing("ConfigMap", name, optional, "envFrom");
                return;
            }
            data.get().forEach((k, v) ->
                    put(merged, prefix + k, new EnvVar(prefix + k, v, EnvVar.Source.CONFIG_MAP, "configMap/" + name)));
        }

        Map<String, Object> secretRef = Yaml.asMap(entry.get("secretRef"));
        if (secretRef != null) {
            String name = Yaml.scalar(secretRef.get("name"));
            boolean optional = Yaml.isTrue(secretRef.get("optional"));
            Optional<Map<String, String>> data = registry.secret(name);
            if (data.isEmpty()) {
                warnMissing("Secret", name, optional, "envFrom");
                return;
            }
            data.get().forEach((k, v) ->
                    put(merged, prefix + k, new EnvVar(prefix + k, v, EnvVar.Source.SECRET, "secret/" + name)));
        }
    }

    private void extractEnv(Map<String, Object> entry, String origin, Map<String, EnvVar> merged) {
        if (entry == null) {
            return;
        }
        String name = Yaml.scalar(entry.get("name"));
        if (name == null) {
            return;
        }

        if (entry.containsKey("value")) {
            put(merged, name, new EnvVar(name, Yaml.scalar(entry.get("value")), EnvVar.Source.LITERAL, origin));
            return;
        }

        Map<String, Object> valueFrom = Yaml.asMap(entry.get("valueFrom"));
        if (valueFrom == null) {
            // An env entry with neither value nor valueFrom resolves to "" in Kubernetes.
            put(merged, name, new EnvVar(name, "", EnvVar.Source.LITERAL, origin));
            return;
        }

        Map<String, Object> configMapKeyRef = Yaml.asMap(valueFrom.get("configMapKeyRef"));
        if (configMapKeyRef != null) {
            resolveKeyRef(name, configMapKeyRef, EnvVar.Source.CONFIG_MAP, merged);
            return;
        }
        Map<String, Object> secretKeyRef = Yaml.asMap(valueFrom.get("secretKeyRef"));
        if (secretKeyRef != null) {
            resolveKeyRef(name, secretKeyRef, EnvVar.Source.SECRET, merged);
            return;
        }
        if (valueFrom.containsKey("fieldRef")) {
            putUnresolved(name, EnvVar.Source.FIELD_REF, "fieldRef (resolved at pod runtime)", merged);
            return;
        }
        if (valueFrom.containsKey("resourceFieldRef")) {
            putUnresolved(name, EnvVar.Source.RESOURCE_FIELD_REF, "resourceFieldRef (resolved at pod runtime)", merged);
            return;
        }
    }

    private void resolveKeyRef(String name, Map<String, Object> ref, EnvVar.Source source, Map<String, EnvVar> merged) {
        String refName = Yaml.scalar(ref.get("name"));
        String key = Yaml.scalar(ref.get("key"));
        boolean optional = Yaml.isTrue(ref.get("optional"));
        String kind = source == EnvVar.Source.CONFIG_MAP ? "ConfigMap" : "Secret";
        Optional<String> value = source == EnvVar.Source.CONFIG_MAP
                ? registry.configMapValue(refName, key)
                : registry.secretValue(refName, key);
        if (value.isPresent()) {
            String origin = (source == EnvVar.Source.CONFIG_MAP ? "configMap/" : "secret/") + refName + ":" + key;
            put(merged, name, new EnvVar(name, value.get(), source, origin));
        } else {
            warn.accept("Env var '" + name + "' references " + kind + " '" + refName + "' key '" + key
                    + "' which was not found in the input"
                    + (optional ? " (marked optional)." : "."));
            if (options.includeUnresolved()) {
                put(merged, name, new EnvVar(name, null, source, kind + "/" + refName + ":" + key + " (unresolved)"));
            }
        }
    }

    private void putUnresolved(String name, EnvVar.Source source, String origin, Map<String, EnvVar> merged) {
        warn.accept("Env var '" + name + "' uses " + origin + " and cannot be resolved statically.");
        if (options.includeUnresolved()) {
            put(merged, name, new EnvVar(name, null, source, origin));
        }
    }

    private void put(Map<String, EnvVar> merged, String name, EnvVar value) {
        EnvVar previous = merged.put(name, value);
        if (previous != null && !equalValue(previous, value)) {
            warn.accept("Env var '" + name + "' is defined more than once; using the value from "
                    + value.origin() + " (overriding " + previous.origin() + ").");
        }
    }

    private static boolean equalValue(EnvVar a, EnvVar b) {
        return java.util.Objects.equals(a.value(), b.value());
    }

    private void warnMissing(String kind, String name, boolean optional, String via) {
        warn.accept(via + " references " + kind + " '" + name
                + "' which was not found in the input"
                + (optional ? " (marked optional); skipping." : "; skipping."));
    }

    private List<Map<String, Object>> containers(Map<String, Object> podSpec) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Object c : Yaml.asList(podSpec.get("containers"))) {
            Map<String, Object> map = Yaml.asMap(c);
            if (map != null) {
                all.add(map);
            }
        }
        if (options.includeInitContainers()) {
            for (Object c : Yaml.asList(podSpec.get("initContainers"))) {
                Map<String, Object> map = Yaml.asMap(c);
                if (map != null) {
                    all.add(map);
                }
            }
        }
        return all;
    }

    /** Locate the {@code PodSpec} for a workload resource, or {@code null} if it is not a workload. */
    private static Map<String, Object> podSpecOf(K8sResource resource) {
        if (resource.hasKind("Pod")) {
            return Yaml.digMap(resource.raw(), "spec");
        }
        if (resource.hasKind("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet",
                "ReplicationController", "Job")) {
            return Yaml.digMap(resource.raw(), "spec", "template", "spec");
        }
        if (resource.hasKind("CronJob")) {
            return Yaml.digMap(resource.raw(), "spec", "jobTemplate", "spec", "template", "spec");
        }
        return null;
    }
}
