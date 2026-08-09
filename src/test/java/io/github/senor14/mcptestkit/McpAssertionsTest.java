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
    void declaresToolsCapabilityChecksInitializeResult() {
        McpTestClient declaring = new FakeMcpTestClient(true).withCapabilities("{\"tools\": {}}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(declaring).declaresToolsCapability());

        McpTestClient silent = new FakeMcpTestClient(true).withCapabilities("{}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(silent).declaresToolsCapability());
    }

    @Test
    void detectsDuplicateToolNames() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"search\", \"description\": \"a\"}")
                .withTool("{\"name\": \"search\", \"description\": \"b\"}");
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolNamesAreUnique());
        assertTrue(error.getMessage().contains("search"));
    }

    @Test
    void enforcesToolNamingConvention() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"good_name\", \"description\": \"x\"}")
                .withTool("{\"name\": \"BadName\", \"description\": \"x\"}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(client).toolNamesMatch("[A-Za-z0-9_]+"));
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).toolNamesMatch("[a-z0-9_]+"));
        assertTrue(error.getMessage().contains("BadName"));
    }

    @Test
    void checksNegotiatedProtocolVersion() {
        McpTestClient client = new FakeMcpTestClient(true);
        assertDoesNotThrow(() -> McpAssertions.assertThat(client)
                .negotiatedProtocolVersionIsOneOf("2025-11-25", "2026-07-28"));
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(client)
                .negotiatedProtocolVersionIsOneOf("2025-11-25"));
    }

    @Test
    void perToolTokenBudgetPinpointsOffender() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withTool("{\"name\": \"small\", \"description\": \"ok\"}")
                .withTool("{\"name\": \"huge\", \"description\": \"" + "word ".repeat(300) + "\"}");
        AssertionError error = assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).eachToolWithinTokenBudget(100));
        assertTrue(error.getMessage().contains("huge"));
        assertTrue(!error.getMessage().contains("small (~"));
    }

    @Test
    void resourceAssertionsCoverUniquenessNamesAndRead() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withResource("{\"uri\": \"app://a\", \"name\": \"a\"}")
                .withResource("{\"uri\": \"app://a\", \"name\": \"dup\"}")
                .withResource("{\"uri\": \"app://b\", \"name\": \"\"}");
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(client).resourceUrisAreUnique());
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(client).resourcesHaveNames());
        assertDoesNotThrow(() -> McpAssertions.assertThat(client).hasResources()
                .readResourceSucceeds("app://a"));

        McpTestClient badRead = new FakeMcpTestClient(true)
                .withReadResourceResult("{\"contents\": [{\"uri\": \"app://a\"}]}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(badRead).readResourceSucceeds("app://a"));
    }

    @Test
    void promptAssertionsCoverUniquenessArgumentsAndGet() {
        McpTestClient client = new FakeMcpTestClient(true)
                .withPrompt("{\"name\": \"greet\", \"arguments\": [{\"name\": \"who\"}]}")
                .withPrompt("{\"name\": \"greet\"}")
                .withPrompt("{\"name\": \"broken\", \"arguments\": [{\"description\": \"nameless\"}]}");
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(client).promptNamesAreUnique());
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(client).promptArgumentsAreWellFormed());
        assertDoesNotThrow(() -> McpAssertions.assertThat(client).hasPrompts()
                .getPromptSucceeds("greet", Map.of("who", "world")));

        McpTestClient badRole = new FakeMcpTestClient(true).withGetPromptResult(
                "{\"messages\": [{\"role\": \"system\", \"content\": {\"type\": \"text\", \"text\": \"x\"}}]}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(badRole).getPromptSucceeds("greet", Map.of()));
    }

    @Test
    void outputSchemaConformanceChecksStructuredContent() {
        String toolWithOutput = """
                {"name": "add", "description": "x",
                 "inputSchema": {"type": "object"},
                 "outputSchema": {"type": "object",
                                  "properties": {"sum": {"type": "number"}},
                                  "required": ["sum"]}}""";
        McpTestClient conforming = new FakeMcpTestClient(true).withTool(toolWithOutput)
                .withCallResult("{\"isError\": false, \"structuredContent\": {\"sum\": 5}}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(conforming)
                .toolOutputSchemasAreValid()
                .callToolConformsToOutputSchema("add", Map.of()));

        McpTestClient missingStructured = new FakeMcpTestClient(true).withTool(toolWithOutput)
                .withCallResult("{\"isError\": false}");
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(missingStructured)
                .callToolConformsToOutputSchema("add", Map.of()));

        McpTestClient wrongType = new FakeMcpTestClient(true).withTool(toolWithOutput)
                .withCallResult("{\"isError\": false, \"structuredContent\": {\"sum\": \"five\"}}");
        assertThrows(AssertionError.class, () -> McpAssertions.assertThat(wrongType)
                .callToolConformsToOutputSchema("add", Map.of()));
    }

    @Test
    void errorPathAssertionsInspectRawExchanges() {
        McpTestClient conforming = new FakeMcpTestClient(true)
                .withExchangeResponse("mcp_testkit/no_such_method",
                        "{\"jsonrpc\": \"2.0\", \"id\": 1, \"error\": {\"code\": -32601, \"message\": \"nf\"}}")
                .withExchangeResponse("tools/call",
                        "{\"jsonrpc\": \"2.0\", \"id\": 2, \"result\": {\"isError\": true, \"content\": []}}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(conforming)
                .unknownMethodYieldsMethodNotFound()
                .unknownToolHandledGracefully());

        McpTestClient silentSuccess = new FakeMcpTestClient(true)
                .withExchangeResponse("tools/call",
                        "{\"jsonrpc\": \"2.0\", \"id\": 2, \"result\": {\"isError\": false, \"content\": []}}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(silentSuccess).unknownToolHandledGracefully());
    }

    @Test
    void capabilityAssertionsCoverResourcesAndPrompts() {
        McpTestClient full = new FakeMcpTestClient(true)
                .withCapabilities("{\"tools\": {}, \"resources\": {}, \"prompts\": {}}");
        assertDoesNotThrow(() -> McpAssertions.assertThat(full)
                .declaresToolsCapability()
                .declaresResourcesCapability()
                .declaresPromptsCapability());
        McpTestClient toolsOnly = new FakeMcpTestClient(true).withCapabilities("{\"tools\": {}}");
        assertThrows(AssertionError.class,
                () -> McpAssertions.assertThat(toolsOnly).declaresResourcesCapability());
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
