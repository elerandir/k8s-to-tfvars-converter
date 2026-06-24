package com.elerandir.k8stotfvars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elerandir.k8stotfvars.model.EnvVar;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ConverterTest {

    private Converter.Result convertSample(EnvVarExtractor.Options options) {
        try (InputStream in = getClass().getResourceAsStream("/sample-app.yaml");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Converter(options, false).convert(reader, "sample-app.yaml");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, EnvVar> byName(Converter.Result result) {
        return result.envVars().stream()
                .collect(Collectors.toMap(EnvVar::name, Function.identity()));
    }

    @Test
    void putsLiteralAndConfigMapValuesIntoEnvVars() {
        Map<String, EnvVar> vars = byName(convertSample(EnvVarExtractor.Options.defaults()));

        assertEquals("info", vars.get("LOG_LEVEL").value());
        assertFalse(vars.get("LOG_LEVEL").isSecret());
        // configMapKeyRef resolved
        assertEquals("postgres://db:5432/app", vars.get("DATABASE_URL").value());
        // envFrom configMapRef expanded
        assertEquals("postgres://db:5432/app", vars.get("database_url").value());
        assertEquals("a,b,c", vars.get("feature_flags").value());
    }

    @Test
    void putsSecretKeysIntoSecretsWithoutResolvingValues() {
        Map<String, EnvVar> vars = byName(convertSample(EnvVarExtractor.Options.defaults()));

        // secretKeyRef: value not resolved, key recorded
        EnvVar apiToken = vars.get("API_TOKEN");
        assertTrue(apiToken.isSecret());
        assertNull(apiToken.value());
        assertEquals("api_token", apiToken.secretKey());

        // envFrom secretRef with prefix: each key recorded under prefixed name
        assertEquals("api_token", vars.get("SECRET_api_token").secretKey());
        assertEquals("smtp_password", vars.get("SECRET_smtp_password").secretKey());
    }

    @Test
    void rendersTwoMapsWithExpectedEntries() {
        String tfvars = convertSample(EnvVarExtractor.Options.defaults()).tfvars();

        assertTrue(tfvars.contains("env_vars = {"), tfvars);
        assertTrue(tfvars.contains("secrets = {"), tfvars);
        assertTrue(tfvars.contains("LOG_LEVEL"), tfvars);
        assertTrue(tfvars.contains("API_TOKEN"), tfvars);
        // secret value is the key, not the resolved secret
        assertTrue(tfvars.contains("\"api_token\""), tfvars);
        assertFalse(tfvars.contains("s3cr3t"), "secret values must not be resolved: " + tfvars);
    }

    @Test
    void retainsManifestCommentsAsTerraformComments() {
        String tfvars = convertSample(EnvVarExtractor.Options.defaults()).tfvars();

        assertTrue(tfvars.contains("# how chatty the logs are"), tfvars);   // block on env entry
        assertTrue(tfvars.contains("# primary database"), tfvars);          // inline on env entry
        assertTrue(tfvars.contains("# token for the upstream API"), tfvars);// block on secret env entry
        assertTrue(tfvars.contains("# comma-separated feature toggles"), tfvars); // from ConfigMap data
        assertTrue(tfvars.contains("# used by the mailer"), tfvars);        // from Secret stringData
    }

    @Test
    void dropsUnresolvableFieldRefByDefaultButWarns() {
        Converter.Result result = convertSample(EnvVarExtractor.Options.defaults());
        assertFalse(byName(result).containsKey("POD_IP"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("POD_IP")),
                "expected a warning about POD_IP");
    }

    @Test
    void includesUnresolvedWhenRequested() {
        Converter.Result result = convertSample(new EnvVarExtractor.Options(null, false, true));
        EnvVar podIp = byName(result).get("POD_IP");
        assertTrue(podIp != null && !podIp.resolved());
    }

    @Test
    void containerFilterRestrictsExtraction() {
        Converter.Result noMatch = convertSample(new EnvVarExtractor.Options("missing", false, false));
        assertTrue(noMatch.envVars().isEmpty());
    }
}
