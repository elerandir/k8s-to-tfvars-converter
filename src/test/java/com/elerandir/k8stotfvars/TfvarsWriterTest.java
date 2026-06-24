package com.elerandir.k8stotfvars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elerandir.k8stotfvars.model.EnvVar;

import java.util.List;

import org.junit.jupiter.api.Test;

class TfvarsWriterTest {

    @Test
    void quotesAndEscapesSpecialCharacters() {
        assertEquals("\"plain\"", TfvarsWriter.quote("plain"));
        assertEquals("\"a\\\"b\"", TfvarsWriter.quote("a\"b"));
        assertEquals("\"a\\\\b\"", TfvarsWriter.quote("a\\b"));
        assertEquals("\"line1\\nline2\"", TfvarsWriter.quote("line1\nline2"));
        assertEquals("\"tab\\there\"", TfvarsWriter.quote("tab\there"));
    }

    @Test
    void escapesTerraformTemplateSequences() {
        assertEquals("\"$${var.x}\"", TfvarsWriter.quote("${var.x}"));
        assertEquals("\"%%{if true}\"", TfvarsWriter.quote("%{if true}"));
    }

    @Test
    void sanitizesInvalidKeyCharacters() {
        assertEquals("FOO_BAR", TfvarsWriter.sanitizeKey("FOO.BAR"));
        assertEquals("_9LIVES", TfvarsWriter.sanitizeKey("9LIVES"));
        assertEquals("keep-dash_ok", TfvarsWriter.sanitizeKey("keep-dash_ok"));
    }

    @Test
    void rendersSortedAssignmentsWithoutHeader() {
        List<EnvVar> vars = List.of(
                new EnvVar("ZEBRA", "1", EnvVar.Source.LITERAL, "x"),
                new EnvVar("ALPHA", "2", EnvVar.Source.LITERAL, "x"));
        String out = new TfvarsWriter(false).render(vars);
        assertEquals("ALPHA = \"2\"\nZEBRA = \"1\"\n", out);
    }

    @Test
    void rendersUnresolvedAsComment() {
        List<EnvVar> vars = List.of(
                new EnvVar("POD_IP", null, EnvVar.Source.FIELD_REF, "fieldRef"));
        String out = new TfvarsWriter(false).render(vars);
        assertTrue(out.startsWith("# POD_IP = null"), out);
    }
}
