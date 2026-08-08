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
}
