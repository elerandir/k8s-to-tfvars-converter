package com.elerandir.k8stotfvars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elerandir.k8stotfvars.EnvVarExtractor.Options;
import com.elerandir.k8stotfvars.model.EnvVar;

import java.io.StringReader;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Behaviour specification for the converter: feed it realistic Kubernetes
 * manifests and assert what the tool actually produces — the two output maps,
 * the warnings, and the rendered file.
 */
@DisplayName("The converter")
class ConverterBehaviorTest {

    // --- helpers ----------------------------------------------------------

    private Converter.Result convert(String manifest) {
        return convert(manifest, Options.defaults());
    }

    private Converter.Result convert(String manifest, Options options) {
        return new Converter(options, false).convert(new StringReader(manifest));
    }

    private Converter.Result convertWithHeader(String manifest) {
        return new Converter(Options.defaults(), true).convert(new StringReader(manifest));
    }

    private Map<String, EnvVar> envByName(Converter.Result result) {
        return result.envVars().stream().collect(Collectors.toMap(EnvVar::name, Function.identity()));
    }

    private boolean warnedAbout(Converter.Result result, String fragment) {
        return result.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    // --- specs ------------------------------------------------------------

    @Nested
    @DisplayName("with plain environment variables")
    class EnvironmentVariables {

        @Test
        @DisplayName("puts literal env values into env_vars")
        void literalValuesGoIntoEnvVars() {
            Converter.Result result = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: LOG_LEVEL
                                  value: info
                    """);

            EnvVar logLevel = envByName(result).get("LOG_LEVEL");
            assertEquals("info", logLevel.value());
            assertFalse(logLevel.isSecret());
        }

        @Test
        @DisplayName("resolves a configMapKeyRef to the ConfigMap's value")
        void configMapKeyRefIsResolved() {
            Converter.Result result = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: DATABASE_URL
                                  valueFrom:
                                    configMapKeyRef:
                                      name: cfg
                                      key: db
                    ---
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: cfg
                    data:
                      db: postgres://db:5432/app
                    """);

            assertEquals("postgres://db:5432/app", envByName(result).get("DATABASE_URL").value());
        }

        @Test
        @DisplayName("keeps the variable name verbatim (case and separators)")
        void variableNamesArePreservedVerbatim() {
            Converter.Result result = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: My_Mixed-Case.NAME
                                  value: x
                    """);

            assertTrue(envByName(result).containsKey("My_Mixed-Case.NAME"));
        }

        @Test
        @DisplayName("treats an env entry with neither value nor valueFrom as an empty string")
        void bareEnvEntryBecomesEmptyString() {
            Converter.Result result = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: EMPTY
                    """);

            assertEquals("", envByName(result).get("EMPTY").value());
        }
    }

    @Nested
    @DisplayName("with secret-backed variables")
    class Secrets {

        private static final String MANIFEST = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          env:
                            - name: API_TOKEN
                              valueFrom:
                                secretKeyRef:
                                  name: sec
                                  key: api_token
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  name: sec
                data:
                  api_token: czNjcjN0LXRva2Vu
                """;

        @Test
        @DisplayName("records the Secret key instead of resolving the value")
        void recordsTheSecretKey() {
            EnvVar token = envByName(convert(MANIFEST)).get("API_TOKEN");
            assertTrue(token.isSecret());
            assertNull(token.value());
            assertEquals("api_token", token.secretKey());
        }

        @Test
        @DisplayName("never leaks the decoded secret value into the output")
        void neverResolvesTheSecretValue() {
            String tfvars = convert(MANIFEST).tfvars();
            assertTrue(tfvars.contains("\"API_TOKEN\" = \"api_token\""), tfvars);
            assertFalse(tfvars.contains("s3cr3t"), tfvars);
        }
    }

    @Nested
    @DisplayName("when deciding which entries to include")
    class ExplicitReferencesOnly {

        private static final String MANIFEST = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          env:
                            - name: USED
                              valueFrom:
                                configMapKeyRef:
                                  name: cfg
                                  key: used
                          envFrom:
                            - configMapRef:
                                name: cfg
                            - secretRef:
                                name: sec
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: cfg
                data:
                  used: yes-please
                  unused: never-referenced
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  name: sec
                data:
                  pw: cHc=
                """;

        @Test
        @DisplayName("includes only entries referenced by an explicit env entry")
        void unreferencedConfigMapEntriesAreExcluded() {
            Map<String, EnvVar> vars = envByName(convert(MANIFEST));
            assertTrue(vars.containsKey("USED"));
            assertFalse(vars.containsKey("unused"));
        }

        @Test
        @DisplayName("skips bulk envFrom imports and warns about each")
        void envFromImportsAreSkippedWithWarnings() {
            Converter.Result result = convert(MANIFEST);
            Map<String, EnvVar> vars = envByName(result);
            assertFalse(vars.containsKey("used"));
            assertFalse(vars.containsKey("pw"));
            assertTrue(warnedAbout(result, "Skipping envFrom import of ConfigMap"));
            assertTrue(warnedAbout(result, "Skipping envFrom import of Secret"));
        }
    }

    @Nested
    @DisplayName("when retaining manifest comments")
    class Comments {

        @Test
        @DisplayName("keeps a block comment written above an env entry")
        void retainsBlockComment() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                # how chatty the logs are
                                - name: LOG_LEVEL
                                  value: info
                    """).tfvars();

            assertTrue(tfvars.contains("# how chatty the logs are"), tfvars);
        }

        @Test
        @DisplayName("keeps an inline comment trailing an env entry")
        void retainsInlineComment() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: LOG_LEVEL # noisy
                                  value: info
                    """).tfvars();

            assertTrue(tfvars.contains("# noisy"), tfvars);
        }

        @Test
        @DisplayName("falls back to the ConfigMap data entry's comment when the env entry has none")
        void fallsBackToConfigMapComment() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: FLAGS
                                  valueFrom:
                                    configMapKeyRef:
                                      name: cfg
                                      key: flags
                    ---
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: cfg
                    data:
                      # comma-separated toggles
                      flags: a,b,c
                    """).tfvars();

            assertTrue(tfvars.contains("# comma-separated toggles"), tfvars);
        }

        @Test
        @DisplayName("prefers the env entry's own comment over the referenced data entry's")
        void envEntryCommentTakesPrecedence() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: FLAGS # from the deployment
                                  valueFrom:
                                    configMapKeyRef:
                                      name: cfg
                                      key: flags
                    ---
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: cfg
                    data:
                      # from the configmap
                      flags: a,b,c
                    """).tfvars();

            assertTrue(tfvars.contains("# from the deployment"), tfvars);
            assertFalse(tfvars.contains("# from the configmap"), tfvars);
        }
    }

    @Nested
    @DisplayName("across workload kinds")
    class WorkloadKinds {

        static Stream<Arguments> workloads() {
            return Stream.of(
                    Arguments.of("Deployment", """
                            apiVersion: apps/v1
                            kind: Deployment
                            metadata: { name: w }
                            spec:
                              template:
                                spec:
                                  containers:
                                    - name: app
                                      env:
                                        - name: LOG_LEVEL
                                          value: info
                            """),
                    Arguments.of("StatefulSet", """
                            apiVersion: apps/v1
                            kind: StatefulSet
                            metadata: { name: w }
                            spec:
                              template:
                                spec:
                                  containers:
                                    - name: app
                                      env:
                                        - name: LOG_LEVEL
                                          value: info
                            """),
                    Arguments.of("DaemonSet", """
                            apiVersion: apps/v1
                            kind: DaemonSet
                            metadata: { name: w }
                            spec:
                              template:
                                spec:
                                  containers:
                                    - name: app
                                      env:
                                        - name: LOG_LEVEL
                                          value: info
                            """),
                    Arguments.of("Job", """
                            apiVersion: batch/v1
                            kind: Job
                            metadata: { name: w }
                            spec:
                              template:
                                spec:
                                  containers:
                                    - name: app
                                      env:
                                        - name: LOG_LEVEL
                                          value: info
                            """),
                    Arguments.of("CronJob", """
                            apiVersion: batch/v1
                            kind: CronJob
                            metadata: { name: w }
                            spec:
                              schedule: "* * * * *"
                              jobTemplate:
                                spec:
                                  template:
                                    spec:
                                      containers:
                                        - name: app
                                          env:
                                            - name: LOG_LEVEL
                                              value: info
                            """),
                    Arguments.of("Pod", """
                            apiVersion: v1
                            kind: Pod
                            metadata: { name: w }
                            spec:
                              containers:
                                - name: app
                                  env:
                                    - name: LOG_LEVEL
                                      value: info
                            """));
        }

        @ParameterizedTest(name = "extracts env vars from a {0}")
        @MethodSource("workloads")
        void extractsFromEachWorkloadKind(String kind, String manifest) {
            assertEquals("info", envByName(convert(manifest)).get("LOG_LEVEL").value());
        }

        @Test
        @DisplayName("ignores initContainers by default but reads them when asked")
        void initContainersAreOptional() {
            String manifest = """
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          initContainers:
                            - name: init
                              env:
                                - name: INIT_ONLY
                                  value: "1"
                          containers:
                            - name: app
                              env:
                                - name: MAIN
                                  value: "2"
                    """;

            assertFalse(envByName(convert(manifest)).containsKey("INIT_ONLY"));

            Converter.Result withInit = convert(manifest, new Options(null, true, false));
            assertTrue(envByName(withInit).containsKey("INIT_ONLY"));
            assertTrue(envByName(withInit).containsKey("MAIN"));
        }
    }

    @Nested
    @DisplayName("with values that cannot be resolved statically")
    class UnresolvableValues {

        private static final String MANIFEST = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          env:
                            - name: POD_IP
                              valueFrom:
                                fieldRef:
                                  fieldPath: status.podIP
                """;

        @Test
        @DisplayName("drops a fieldRef and warns, by default")
        void fieldRefDroppedWithWarning() {
            Converter.Result result = convert(MANIFEST);
            assertFalse(envByName(result).containsKey("POD_IP"));
            assertTrue(warnedAbout(result, "POD_IP"));
        }

        @Test
        @DisplayName("emits unresolved vars as commented placeholders when asked")
        void includesUnresolvedAsComment() {
            Converter.Result result = convert(MANIFEST, new Options(null, false, true));
            assertEquals(1, result.unresolvedCount());
            assertTrue(result.tfvars().contains("# \"POD_IP\" = null"), result.tfvars());
        }
    }

    @Nested
    @DisplayName("when a name is defined more than once")
    class ConflictingDefinitions {

        @Test
        @DisplayName("keeps the last definition and warns about the override")
        void lastDefinitionWinsAndWarns() {
            Converter.Result result = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: first
                              env:
                                - name: DUP
                                  value: one
                            - name: second
                              env:
                                - name: DUP
                                  value: two
                    """);

            assertEquals("two", envByName(result).get("DUP").value());
            assertTrue(warnedAbout(result, "defined more than once"));
        }
    }

    @Nested
    @DisplayName("when filtering by container")
    class ContainerFiltering {

        private static final String MANIFEST = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          env:
                            - name: APP_VAR
                              value: a
                        - name: sidecar
                          env:
                            - name: SIDE_VAR
                              value: s
                """;

        @Test
        @DisplayName("reads only the named container")
        void onlyTheNamedContainer() {
            Map<String, EnvVar> vars = envByName(convert(MANIFEST, new Options("app", false, false)));
            assertTrue(vars.containsKey("APP_VAR"));
            assertFalse(vars.containsKey("SIDE_VAR"));
        }

        @Test
        @DisplayName("produces nothing when no container matches")
        void unknownContainerYieldsNothing() {
            assertTrue(convert(MANIFEST, new Options("missing", false, false)).envVars().isEmpty());
        }
    }

    @Nested
    @DisplayName("when reading multiple documents")
    class MultipleDocuments {

        @Test
        @DisplayName("resolves references across documents regardless of order")
        void resolvesAcrossDocumentsIrrespectiveOfOrder() {
            // ConfigMap appears before the Deployment that references it.
            Converter.Result result = convert("""
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: cfg
                    data:
                      db: the-url
                    ---
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: DB
                                  valueFrom:
                                    configMapKeyRef:
                                      name: cfg
                                      key: db
                    """);

            assertEquals("the-url", envByName(result).get("DB").value());
        }

        @Test
        @DisplayName("flattens a kind: List wrapper")
        void flattensKindList() {
            Converter.Result result = convert("""
                    apiVersion: v1
                    kind: List
                    items:
                      - apiVersion: apps/v1
                        kind: Deployment
                        metadata:
                          name: web
                        spec:
                          template:
                            spec:
                              containers:
                                - name: app
                                  env:
                                    - name: LOG_LEVEL
                                      value: info
                    """);

            assertEquals("info", envByName(result).get("LOG_LEVEL").value());
        }
    }

    @Nested
    @DisplayName("when there is nothing to extract")
    class NoWorkloads {

        @Test
        @DisplayName("warns when the input contains no workloads")
        void warnsWhenNoWorkloads() {
            Converter.Result result = convert("""
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: cfg
                    data:
                      a: b
                    """);

            assertTrue(result.envVars().isEmpty());
            assertTrue(warnedAbout(result, "No workload resources"));
        }
    }

    @Nested
    @DisplayName("when rendering the .tfvars file")
    class Rendering {

        @Test
        @DisplayName("always emits both maps, empty when there is nothing")
        void bothMapsAlwaysPresent() {
            String tfvars = convert("""
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: p
                    spec:
                      containers:
                        - name: app
                    """).tfvars();

            assertEquals("env_vars = {}\n\nsecrets = {}\n", tfvars);
        }

        @Test
        @DisplayName("quotes keys, sorts entries, and aligns the equals signs")
        void quotedSortedAligned() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: BBBBB
                                  value: "2"
                                - name: AAA
                                  value: "1"
                    """).tfvars();

            assertEquals("""
                    env_vars = {
                      "AAA"   = "1"
                      "BBBBB" = "2"
                    }

                    secrets = {}
                    """, tfvars);
        }

        @Test
        @DisplayName("escapes HCL special characters in values")
        void escapesHclSpecialCharacters() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: TRICKY
                                  value: "a\\"b\\\\c\\tx\\ny"
                    """).tfvars();

            assertTrue(tfvars.contains("\"TRICKY\" = \"a\\\"b\\\\c\\tx\\ny\""), tfvars);
        }

        @Test
        @DisplayName("escapes Terraform template sequences so they are not interpolated")
        void escapesTemplateSequences() {
            String tfvars = convert("""
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: web
                    spec:
                      template:
                        spec:
                          containers:
                            - name: app
                              env:
                                - name: TPL
                                  value: "${x}-%{y}"
                    """).tfvars();

            assertTrue(tfvars.contains("\"TPL\" = \"$${x}-%%{y}\""), tfvars);
        }

        @Test
        @DisplayName("includes the generated-file header only when enabled")
        void headerTogglesWithTheOption() {
            String manifest = """
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: p
                    spec:
                      containers:
                        - name: app
                          env:
                            - name: A
                              value: b
                    """;

            assertTrue(convertWithHeader(manifest).tfvars().startsWith("# Generated by"));
            assertFalse(convert(manifest).tfvars().contains("# Generated by"));
        }
    }
}
