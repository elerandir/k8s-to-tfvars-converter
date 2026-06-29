package com.elerandir.k8stotfvars;

import com.elerandir.k8stotfvars.model.Comment;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Helpers for navigating SnakeYAML {@link Node} trees and extracting the
 * comments attached to entries. Working at the node level (rather than the
 * plain {@code Map} produced by the default loader) is what lets the converter
 * preserve manifest comments.
 *
 * <p>Stateless, but injected as a singleton so collaborators receive it through
 * the Dagger graph rather than reaching for static methods.
 */
@Singleton
class NodeYaml {

    @Inject
    NodeYaml() {
    }

    MappingNode asMapping(Node node) {
        return node instanceof MappingNode m ? m : null;
    }

    List<Node> sequence(Node node) {
        return node instanceof SequenceNode s ? s.getValue() : List.of();
    }

    String scalar(Node node) {
        return node instanceof ScalarNode s ? s.getValue() : null;
    }

    boolean isTrue(Node node) {
        return "true".equals(scalar(node));
    }

    /** The value node for {@code key} in a mapping, or {@code null} if absent. */
    Node get(MappingNode mapping, String key) {
        if (mapping == null) {
            return null;
        }
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode s && key.equals(s.getValue())) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    MappingNode getMapping(MappingNode mapping, String key) {
        return asMapping(get(mapping, key));
    }

    /** Navigate a chain of mapping keys, returning {@code null} if any link is missing. */
    MappingNode digMapping(MappingNode root, String... path) {
        MappingNode current = root;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            current = getMapping(current, key);
        }
        return current;
    }

    boolean hasKey(MappingNode mapping, String key) {
        return mapping != null && get(mapping, key) != null;
    }

    /**
     * Extract the comment for an entry that is itself a mapping (e.g. an item in
     * an {@code env:} sequence). The block comment attaches to the mapping's first
     * key; the inline comment attaches to the value of the {@code name} key (the
     * scalar on the same line as the {@code - name: X} entry).
     */
    Comment commentForMappingEntry(MappingNode entry, String inlineKey) {
        if (entry == null || entry.getValue().isEmpty()) {
            return Comment.NONE;
        }
        Node firstKey = entry.getValue().get(0).getKeyNode();
        List<String> block = blockLines(firstKey);
        String inline = inlineComment(get(entry, inlineKey));
        return toComment(block, inline);
    }

    /**
     * Extract the comment for a {@code key: value} tuple (e.g. an entry in a
     * ConfigMap/Secret {@code data} mapping). Block attaches to the key node;
     * inline attaches to the value node.
     */
    Comment commentForTuple(NodeTuple tuple) {
        List<String> block = blockLines(tuple.getKeyNode());
        String inline = inlineComment(tuple.getValueNode());
        return toComment(block, inline);
    }

    private Comment toComment(List<String> block, String inline) {
        if (block.isEmpty() && inline == null) {
            return Comment.NONE;
        }
        return new Comment(block, inline);
    }

    private List<String> blockLines(Node node) {
        List<String> lines = new ArrayList<>();
        if (node == null || node.getBlockComments() == null) {
            return lines;
        }
        for (CommentLine line : node.getBlockComments()) {
            if (line.getCommentType() == CommentType.BLANK_LINE) {
                continue;
            }
            lines.add(normalize(line.getValue()));
        }
        return lines;
    }

    private String inlineComment(Node node) {
        if (node == null || node.getInLineComments() == null) {
            return null;
        }
        for (CommentLine line : node.getInLineComments()) {
            if (line.getCommentType() != CommentType.BLANK_LINE) {
                return normalize(line.getValue());
            }
        }
        return null;
    }

    /** SnakeYAML keeps the space after {@code #}; trim a single leading space. */
    private String normalize(String raw) {
        String value = raw == null ? "" : raw;
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        return value.stripTrailing();
    }
}
