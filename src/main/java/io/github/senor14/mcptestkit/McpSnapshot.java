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
            throw new AssertionError(
                    "MCP snapshot '" + name + "' does not match " + file + "\n"
                            + "If this change is intentional, rerun with -Dmcp.snapshot.update=true\n"
                            + "--- expected ---\n" + toJson(expected) + "\n"
                            + "--- actual ---\n" + toJson(actualNode));
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
