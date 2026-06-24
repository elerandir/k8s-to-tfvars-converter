package com.elerandir.k8stotfvars;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.elerandir.k8stotfvars.model.Comment;
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
    }

    @Test
    void escapesTerraformTemplateSequences() {
        assertEquals("\"$${var.x}\"", TfvarsWriter.quote("${var.x}"));
        assertEquals("\"%%{if true}\"", TfvarsWriter.quote("%{if true}"));
    }

    @Test
    void keyTokenIsBareWhenValidOtherwiseQuoted() {
        assertEquals("API_TOKEN", TfvarsWriter.keyToken("API_TOKEN"));
        assertEquals("_9LIVES", TfvarsWriter.keyToken("_9LIVES"));
        assertEquals("\"FOO.BAR\"", TfvarsWriter.keyToken("FOO.BAR"));
    }

    @Test
    void rendersEmptyMaps() {
        assertEquals("env_vars = {}\n\nsecrets = {}\n", new TfvarsWriter(false).render(List.of()));
    }

    @Test
    void rendersBothMapsSortedWithComments() {
        List<EnvVar> vars = List.of(
                new EnvVar("ZEBRA", "1", null, EnvVar.Source.LITERAL, "x",
                        new Comment(List.of("note"), "inline")),
                new EnvVar("ALPHA", "2", null, EnvVar.Source.LITERAL, "x", Comment.NONE),
                new EnvVar("API_TOKEN", null, "api_token", EnvVar.Source.SECRET, "x", Comment.NONE));

        String expected = """
                env_vars = {
                  ALPHA = "2"
                  # note
                  ZEBRA = "1" # inline
                }

                secrets = {
                  API_TOKEN = "api_token"
                }
                """;
        assertEquals(expected, new TfvarsWriter(false).render(vars));
    }

    @Test
    void rendersUnresolvedAsCommentInsideEnvVars() {
        List<EnvVar> vars = List.of(
                new EnvVar("POD_IP", null, null, EnvVar.Source.FIELD_REF, "fieldRef", Comment.NONE));
        String out = new TfvarsWriter(false).render(vars);
        assertEquals("""
                env_vars = {
                  # POD_IP = null  # unresolved: fieldRef
                }

                secrets = {}
                """, out);
    }
}
