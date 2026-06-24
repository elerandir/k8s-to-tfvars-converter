package com.elerandir.k8stotfvars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void resolvesLiteralConfigMapAndSecretReferences() {
        Converter.Result result = convertSample(EnvVarExtractor.Options.defaults());
        Map<String, EnvVar> vars = byName(result);

        assertEquals("info", vars.get("LOG_LEVEL").value());
        assertEquals("postgres://db:5432/app", vars.get("DATABASE_URL").value());
        // Secret data is base64-decoded.
        assertEquals("s3cr3t-token", vars.get("API_TOKEN").value());
    }

    @Test
    void expandsEnvFromConfigMapAndSecretWithPrefix() {
        Map<String, EnvVar> vars = byName(convertSample(EnvVarExtractor.Options.defaults()));

        // envFrom configMapRef (no prefix)
        assertEquals("postgres://db:5432/app", vars.get("database_url").value());
        assertEquals("a,b,c", vars.get("feature_flags").value());
        // envFrom secretRef with prefix SECRET_; stringData kept verbatim
        assertEquals("s3cr3t-token", vars.get("SECRET_api_token").value());
        assertEquals("hunter2", vars.get("SECRET_smtp_password").value());
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
        assertEquals(1, result.unresolvedCount());
    }

    @Test
    void containerFilterRestrictsExtraction() {
        Converter.Result match = convertSample(new EnvVarExtractor.Options("app", false, false));
        assertFalse(match.envVars().isEmpty());

        Converter.Result noMatch = convertSample(new EnvVarExtractor.Options("missing", false, false));
        assertTrue(noMatch.envVars().isEmpty());
    }

    @Test
    void rendersValidSortedTfvars() {
        String tfvars = convertSample(EnvVarExtractor.Options.defaults()).tfvars();
        // Spot-check a couple of rendered lines.
        assertTrue(tfvars.contains("LOG_LEVEL = \"info\"\n"), tfvars);
        assertTrue(tfvars.contains("API_TOKEN = \"s3cr3t-token\"\n"), tfvars);
    }
}
