package com.elerandir.k8stotfvars;

import lombok.experimental.UtilityClass;

/**
 * Names of the Kubernetes manifest fields and resource kinds this tool reads,
 * collected in one place so the schema vocabulary is not scattered as string
 * literals across the parser, registry, and extractor.
 */
@UtilityClass
class K8s {

    // Common metadata fields.
    final String KIND = "kind";
    final String METADATA = "metadata";
    final String NAME = "name";
    final String ITEMS = "items";

    // Pod-spec navigation.
    final String SPEC = "spec";
    final String TEMPLATE = "template";
    final String CONTAINERS = "containers";
    final String INIT_CONTAINERS = "initContainers";

    // Container environment fields.
    final String ENV = "env";
    final String ENV_FROM = "envFrom";
    final String VALUE = "value";
    final String VALUE_FROM = "valueFrom";
    final String CONFIG_MAP_KEY_REF = "configMapKeyRef";
    final String SECRET_KEY_REF = "secretKeyRef";
    final String CONFIG_MAP_REF = "configMapRef";
    final String SECRET_REF = "secretRef";
    final String FIELD_REF = "fieldRef";
    final String RESOURCE_FIELD_REF = "resourceFieldRef";
    final String KEY = "key";
    final String OPTIONAL = "optional";

    // ConfigMap / Secret data fields.
    final String DATA = "data";
    final String STRING_DATA = "stringData";

    // Resource kinds.
    final String KIND_LIST = "List";
    final String KIND_CONFIG_MAP = "ConfigMap";
    final String KIND_SECRET = "Secret";
    final String KIND_DEPLOYMENT = "Deployment";
    final String KIND_STATEFUL_SET = "StatefulSet";
    final String KIND_DAEMON_SET = "DaemonSet";
    final String KIND_REPLICA_SET = "ReplicaSet";
    final String KIND_REPLICATION_CONTROLLER = "ReplicationController";
    final String KIND_JOB = "Job";
}
