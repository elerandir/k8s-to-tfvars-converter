package com.elerandir.k8stotfvars.model;

import java.util.List;

/**
 * Comments captured from a manifest near an env var or secret definition.
 *
 * @param lines  block-comment lines that appeared above the entry, in order,
 *               without the leading {@code #}
 * @param inline a trailing comment on the same line as the entry, without the
 *               leading {@code #}, or {@code null} if there was none
 */
public record Comment(List<String> lines, String inline) {

    public static final Comment NONE = new Comment(List.of(), null);

    public boolean isEmpty() {
        return lines.isEmpty() && inline == null;
    }

    /** Prefer a non-empty comment, falling back to {@code other} when this one is empty. */
    public Comment orElse(Comment other) {
        return isEmpty() ? other : this;
    }
}
