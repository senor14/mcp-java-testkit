package io.github.senor14.mcptestkit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAssertionsTest {

    private static final String VALID_TOOL = """
            {"name": "search", "description": "Searches things.",
             "inputSchema": {"type": "object",
                             "properties": {"query": {"type": "string"}},
                             "required": ["query"]}}""";

    @Test
    void passesForWellFormedServer() {
        McpTestClient client = new FakeMcpTestClient(true).withTool(VALID_TOOL);
        assertDoesNotThrow(() -> McpAssertions.assertThat(client)
                .initializesSuccessfully()
                .hasTools()
                .toolExists("search")
                .toolsHaveDescriptions()
                .toolSchemasAreValid()
                .toolListWithinTokenBudget(1_000));
    }

    @Test
    void failsWhenNotInitialized() {
        McpTestClient client = new FakeMcpTestClient(false);
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).initializesSuccessfully());
    }

    @Test
    void failsWhenNoTools() {
        McpTestClient client = new FakeMcpTestClient(true);
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(client).hasTools());
    }

    @Test
    void failsOnBlankDescription() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"bare\", \"description\": \"  \"}");
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolsHaveDescriptions());
        assertTrue(error.getMessage().contains("bare"));
    }

    @Test
    void failsOnMissingInputSchema() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"schemaless\", \"description\": \"x\"}");
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolSchemasAreValid());
        assertTrue(error.getMessage().contains("schemaless"));
    }

    @Test
    void failsWhenRequiredPropertyUndeclared() {
        McpTestClient client = new FakeMcpTestClient(true).withTool("""
                {"name": "broken", "description": "x",
                 "inputSchema": {"type": "object",
                                 "properties": {"a": {"type": "string"}},
                                 "required": ["a", "ghost"]}}""");
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolSchemasAreValid());
        assertTrue(error.getMessage().contains("ghost"));
    }

    @Test
    void failsWhenOverTokenBudget() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"big\", \"description\": \"" + "word ".repeat(500) + "\"}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolListWithinTokenBudget(50));
    }

    @Test
    void callToolSucceedsChecksIsError() {
        McpTestClient ok = new FakeMcpTestClient(true).withCallResult("{\"isError\": false}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(ok).callToolSucceeds("t", Map.of()));

        McpTestClient failing = new FakeMcpTestClient(true)
                .withCallResult("{\"isError\": true, \"content\": [{\"type\": \"text\", \"text\": \"boom\"}]}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(failing).callToolSucceeds("t", Map.of()));
    }
}
