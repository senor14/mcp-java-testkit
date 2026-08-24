package io.github.senor14.mcptestkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSnapshotTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void pointSnapshotsAtTempDir() {
        System.setProperty("mcp.snapshot.dir", tempDir.toString());
    }

    @AfterEach
    void resetSnapshotDir() {
        System.clearProperty("mcp.snapshot.dir");
        System.clearProperty("mcp.snapshot.update");
    }

    @Test
    void firstRunCreatesSnapshotAndPasses() {
        McpSnapshot.matches("tools", List.of(Map.of("name", "search")));
        assertTrue(Files.exists(tempDir.resolve("tools.json")));
    }

    @Test
    void unchangedValuePasses() {
        McpSnapshot.matches("stable", Map.of("a", 1, "b", 2));
        assertDoesNotThrow(() -> McpSnapshot.matches("stable", Map.of("b", 2, "a", 1)));
    }

    @Test
    void changedValueFails() {
        McpSnapshot.matches("contract", Map.of("version", 1));
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpSnapshot.matches("contract", Map.of("version", 2)));
        assertTrue(error.getMessage().contains("mcp.snapshot.update"));
    }

    @Test
    void updateModeRewritesSnapshot() {
        McpSnapshot.matches("evolving", Map.of("version", 1));
        System.setProperty("mcp.snapshot.update", "true");
        assertDoesNotThrow(() -> McpSnapshot.matches("evolving", Map.of("version", 2)));
        System.clearProperty("mcp.snapshot.update");
        assertDoesNotThrow(() -> McpSnapshot.matches("evolving", Map.of("version", 2)));
    }

    @Test
    void rejectsPathTraversalNames() {
        assertThrows(IllegalArgumentException.class,
                () -> McpSnapshot.matches("../evil", Map.of()));
    }

    @Test
    void toolListMismatchIsSummarizedByCategory() {
        McpSnapshot.matches("toollist", List.of(
                Map.of("name", "search", "description", "Finds things",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))),
                Map.of("name", "delete", "description", "Removes things")));
        AssertionError error = assertThrows(AssertionError.class, () -> McpSnapshot.matches("toollist", List.of(
                Map.of("name", "search", "description", "Finds things NOW WITH ADS",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("q", Map.of("type", "number")))),
                Map.of("name", "create", "description", "Makes things"))));
        String message = error.getMessage();
        assertTrue(message.contains("removed tools"), message);
        assertTrue(message.contains("delete"), message);
        assertTrue(message.contains("added tools"), message);
        assertTrue(message.contains("create"), message);
        assertTrue(message.contains("text changes"), message);
        assertTrue(message.contains("search.description"), message);
        assertTrue(message.contains("structural changes"), message);
        assertTrue(message.contains("search.inputSchema.properties.q.type"), message);
        assertTrue(message.contains("mcp.snapshot.update"), message);
        // The unreadable full dumps are replaced by the summary for tool lists.
        assertTrue(!message.contains("--- expected ---"), message);
    }

    @Test
    void nonToolListMismatchKeepsFullDump() {
        McpSnapshot.matches("plain", Map.of("version", 1));
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpSnapshot.matches("plain", Map.of("version", 2)));
        assertTrue(error.getMessage().contains("--- expected ---"), error.getMessage());
    }

    @Test
    void propertyNamedDescriptionIsStructuralNotText() {
        // A *property* called "description" is schema structure, not model-read text.
        McpSnapshot.matches("propdesc", List.of(
                Map.of("name", "t", "inputSchema",
                        Map.of("properties", Map.of("description", Map.of("type", "string"))))));
        AssertionError error = assertThrows(AssertionError.class, () -> McpSnapshot.matches("propdesc", List.of(
                Map.of("name", "t", "inputSchema",
                        Map.of("properties", Map.of("description", Map.of("type", "number")))))));
        String message = error.getMessage();
        assertTrue(message.contains("structural changes"), message);
        assertTrue(!message.contains("text changes"), message);
    }
}
