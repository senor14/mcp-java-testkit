package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contract snapshot testing for MCP servers.
 *
 * <p>On first run a snapshot file is written and the assertion passes. On later runs the
 * actual value is compared against the stored snapshot and the assertion fails on any
 * difference — catching tool-schema changes that would break existing clients.</p>
 *
 * <p>Snapshots are stored as canonical (key-sorted, pretty-printed) JSON under
 * {@code src/test/resources/__mcp_snapshots__} so diffs are reviewable in pull requests.
 * Override the location with {@code -Dmcp.snapshot.dir=...}. Regenerate intentionally
 * changed snapshots with {@code -Dmcp.snapshot.update=true}.</p>
 */
public final class McpSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private McpSnapshot() {
    }

    /**
     * Asserts that {@code actual} matches the stored snapshot named {@code name},
     * creating the snapshot if it does not exist yet.
     */
    public static void matches(String name, Object actual) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Snapshot name must contain only letters, digits, '.', '_' or '-': " + name);
        }
        JsonNode actualNode = MAPPER.valueToTree(actual);
        Path file = snapshotDir().resolve(name + ".json");

        boolean update = Boolean.getBoolean("mcp.snapshot.update");
        if (update || !Files.exists(file)) {
            write(file, actualNode);
            return;
        }

        JsonNode expected = read(file);
        if (!expected.equals(actualNode)) {
            throw new AssertionError(mismatchMessage(name, file, expected, actualNode));
        }
    }

    /**
     * Builds the failure message. When both sides look like a tool list (an array of objects
     * carrying {@code name}), the diff is summarized by category — removed/added tools, changes
     * to model-read text ({@code description}/{@code title}/{@code annotations}), and structural
     * changes — instead of dumping two full JSON documents. The categories describe <em>where</em>
     * a change happened, not whether it is safe; the assertion fails on any difference either way.
     */
    private static String mismatchMessage(String name, Path file, JsonNode expected, JsonNode actual) {
        StringBuilder sb = new StringBuilder()
                .append("MCP snapshot '").append(name).append("' does not match ").append(file).append('\n');
        if (isToolList(expected) && isToolList(actual)) {
            appendToolListDiff(sb, expected, actual);
            sb.append("If this change is intentional, rerun with -Dmcp.snapshot.update=true\n")
                    .append("(full JSON in the snapshot file above)");
        } else {
            sb.append("If this change is intentional, rerun with -Dmcp.snapshot.update=true\n")
                    .append("--- expected ---\n").append(toJson(expected)).append('\n')
                    .append("--- actual ---\n").append(toJson(actual));
        }
        return sb.toString();
    }

    private static boolean isToolList(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isObject() || !item.path("name").isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static void appendToolListDiff(StringBuilder sb, JsonNode expected, JsonNode actual) {
        java.util.Map<String, JsonNode> before = byName(expected);
        java.util.Map<String, JsonNode> after = byName(actual);

        java.util.List<String> removed = new java.util.ArrayList<>();
        before.keySet().forEach(n -> {
            if (!after.containsKey(n)) {
                removed.add(n);
            }
        });
        java.util.List<String> added = new java.util.ArrayList<>();
        after.keySet().forEach(n -> {
            if (!before.containsKey(n)) {
                added.add(n);
            }
        });

        java.util.List<String> text = new java.util.ArrayList<>();
        java.util.List<String> structural = new java.util.ArrayList<>();
        before.forEach((toolName, beforeTool) -> {
            JsonNode afterTool = after.get(toolName);
            if (afterTool == null || beforeTool.equals(afterTool)) {
                return;
            }
            collectLeafDiffs(toolName, beforeTool, afterTool, (path, from, to) -> {
                String line = path + ": " + from + " -> " + to;
                (isModelReadText(path) ? text : structural).add(line);
            });
        });

        appendSection(sb, "removed tools", removed);
        appendSection(sb, "added tools", added);
        appendSection(sb, "text changes (description/title/annotations — what the model reads)", text);
        appendSection(sb, "structural changes", structural);
    }

    private static java.util.Map<String, JsonNode> byName(JsonNode toolList) {
        java.util.Map<String, JsonNode> tools = new java.util.LinkedHashMap<>();
        toolList.forEach(tool -> tools.put(tool.path("name").asText(), tool));
        return tools;
    }

    private interface DiffSink {
        void accept(String path, String from, String to);
    }

    private static void collectLeafDiffs(String path, JsonNode before, JsonNode after, DiffSink sink) {
        if (before.equals(after)) {
            return;
        }
        if (before.isObject() && after.isObject()) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            before.fieldNames().forEachRemaining(keys::add);
            after.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode b = before.path(key);
                JsonNode a = after.path(key);
                if (b.isMissingNode() || a.isMissingNode()) {
                    sink.accept(path + "." + key, render(b), render(a));
                } else {
                    collectLeafDiffs(path + "." + key, b, a, sink);
                }
            }
        } else if (before.isArray() && after.isArray()) {
            int max = Math.max(before.size(), after.size());
            for (int i = 0; i < max; i++) {
                JsonNode b = before.path(i);
                JsonNode a = after.path(i);
                if (b.isMissingNode() || a.isMissingNode()) {
                    sink.accept(path + "[" + i + "]", render(b), render(a));
                } else {
                    collectLeafDiffs(path + "[" + i + "]", b, a, sink);
                }
            }
        } else {
            sink.accept(path, render(before), render(after));
        }
    }

    /**
     * Whether a diff path points at text the model reads: a leaf field named
     * {@code description}/{@code title} (but not a <em>property</em> with that name — those sit
     * directly under a {@code properties} object and are schema structure), or anything under
     * {@code annotations}.
     */
    private static boolean isModelReadText(String path) {
        String[] segments = path.split("[.\\[]");
        String last = segments[segments.length - 1];
        if (("description".equals(last) || "title".equals(last))
                && (segments.length < 2 || !"properties".equals(segments[segments.length - 2]))) {
            return true;
        }
        for (String segment : segments) {
            if ("annotations".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String render(JsonNode node) {
        if (node.isMissingNode()) {
            return "(absent)";
        }
        String json = node.toString();
        return json.length() <= 80 ? json : json.substring(0, 77) + "...";
    }

    private static void appendSection(StringBuilder sb, String label, java.util.List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        entries.stream().limit(10).forEach(entry -> sb.append("  ").append(entry).append('\n'));
        if (entries.size() > 10) {
            sb.append("  ... and ").append(entries.size() - 10).append(" more\n");
        }
    }

    private static Path snapshotDir() {
        String dir = System.getProperty("mcp.snapshot.dir",
                Path.of("src", "test", "resources", "__mcp_snapshots__").toString());
        return Path.of(dir);
    }

    private static void write(Path file, JsonNode node) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(node) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write snapshot " + file, e);
        }
    }

    private static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot " + file, e);
        }
    }

    private static String toJson(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    MAPPER.treeToValue(node, Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
