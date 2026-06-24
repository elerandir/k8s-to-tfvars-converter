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
        // configMapKeyRef resolved to the ConfigMap value
        assertEquals("postgres://db:5432/app", vars.get("DATABASE_URL").value());
        assertEquals("a,b,c", vars.get("FEATURE_FLAGS").value());
    }

    @Test
    void putsSecretKeysIntoSecretsWithoutResolvingValues() {
        EnvVar apiToken = byName(convertSample(EnvVarExtractor.Options.defaults())).get("API_TOKEN");
        assertTrue(apiToken.isSecret());
        assertNull(apiToken.value());
        assertEquals("api_token", apiToken.secretKey());
    }

    @Test
    void excludesConfigMapEntriesNotReferencedByTheDeployment() {
        Map<String, EnvVar> vars = byName(convertSample(EnvVarExtractor.Options.defaults()));

        // unused_key exists in the ConfigMap but is never referenced -> excluded
        assertFalse(vars.containsKey("unused_key"));
        // envFrom bulk imports are skipped, so the lowercase keys and SECRET_* prefixed
        // keys do not appear; only explicitly referenced entries do.
        assertFalse(vars.containsKey("database_url"));
        assertFalse(vars.containsKey("feature_flags"));
        assertFalse(vars.containsKey("SECRET_api_token"));
        assertFalse(vars.containsKey("SECRET_smtp_password"));
    }

    @Test
    void warnsThatEnvFromImportsAreSkipped() {
        Converter.Result result = convertSample(EnvVarExtractor.Options.defaults());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Skipping envFrom import of ConfigMap")),
                result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Skipping envFrom import of Secret")),
                result.warnings().toString());
    }

    @Test
    void rendersTwoMapsWithQuotedKeys() {
        String tfvars = convertSample(EnvVarExtractor.Options.defaults()).tfvars();

        assertTrue(tfvars.contains("env_vars = {"), tfvars);
        assertTrue(tfvars.contains("secrets = {"), tfvars);
        // keys are quoted (alignment may pad spaces before '=')
        assertTrue(tfvars.matches("(?s).*\"LOG_LEVEL\" +=  ?\"info\".*"), tfvars);
        assertTrue(tfvars.contains("\"API_TOKEN\" = \"api_token\""), tfvars);
        // secret value is the key, never the resolved secret
        assertFalse(tfvars.contains("s3cr3t"), "secret values must not be resolved: " + tfvars);
    }

    @Test
    void retainsManifestComments() {
        String tfvars = convertSample(EnvVarExtractor.Options.defaults()).tfvars();

        assertTrue(tfvars.contains("# how chatty the logs are"), tfvars);     // block on env entry
        assertTrue(tfvars.contains("# primary database"), tfvars);            // inline on env entry
        assertTrue(tfvars.contains("# token for the upstream API"), tfvars);  // block on secret env entry
        // FEATURE_FLAGS has no comment on its env entry; falls back to the ConfigMap entry's comment
        assertTrue(tfvars.contains("# comma-separated feature toggles"), tfvars);
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
