package com.elerandir.k8stotfvars;

/**
 * Names of the Kubernetes manifest fields and resource kinds this tool reads,
 * collected in one place so the schema vocabulary is not scattered as string
 * literals across the parser, registry, and extractor.
 */
final class K8s {

    private K8s() {
    }

    // Common metadata fields.
    static final String KIND = "kind";
    static final String METADATA = "metadata";
    static final String NAME = "name";
    static final String ITEMS = "items";

    // Pod-spec navigation.
    static final String SPEC = "spec";
    static final String TEMPLATE = "template";
    static final String JOB_TEMPLATE = "jobTemplate";
    static final String CONTAINERS = "containers";
    static final String INIT_CONTAINERS = "initContainers";

    // Container environment fields.
    static final String ENV = "env";
    static final String ENV_FROM = "envFrom";
    static final String VALUE = "value";
    static final String VALUE_FROM = "valueFrom";
    static final String CONFIG_MAP_KEY_REF = "configMapKeyRef";
    static final String SECRET_KEY_REF = "secretKeyRef";
    static final String CONFIG_MAP_REF = "configMapRef";
    static final String SECRET_REF = "secretRef";
    static final String FIELD_REF = "fieldRef";
    static final String RESOURCE_FIELD_REF = "resourceFieldRef";
    static final String KEY = "key";
    static final String OPTIONAL = "optional";

    // ConfigMap / Secret data fields.
    static final String DATA = "data";
    static final String STRING_DATA = "stringData";

    // Resource kinds.
    static final String KIND_LIST = "List";
    static final String KIND_POD = "Pod";
    static final String KIND_CONFIG_MAP = "ConfigMap";
    static final String KIND_SECRET = "Secret";
    static final String KIND_DEPLOYMENT = "Deployment";
    static final String KIND_STATEFUL_SET = "StatefulSet";
    static final String KIND_DAEMON_SET = "DaemonSet";
    static final String KIND_REPLICA_SET = "ReplicaSet";
    static final String KIND_REPLICATION_CONTROLLER = "ReplicationController";
    static final String KIND_JOB = "Job";
    static final String KIND_CRON_JOB = "CronJob";
}
