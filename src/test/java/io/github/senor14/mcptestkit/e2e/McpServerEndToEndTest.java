package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end test: launches {@link SampleMcpServer} as a real child JVM over stdio and
 * drives the full flow — handshake, paginated tool listing, tool calls, and teardown.
 */
@McpServerTest(command = {
        "${java.home}/bin/java",
        "-cp", "${java.class.path}",
        "io.github.senor14.mcptestkit.e2e.SampleMcpServer"})
class McpServerEndToEndTest {

    @Test
    void fullConformancePass(McpTestClient client) {
        McpAssertions.assertThat(client)
                .initializesSuccessfully()
                .declaresToolsCapability()
                .negotiatedProtocolVersionIsOneOf(StdioMcpTestClient.REQUESTED_PROTOCOL_VERSION)
                .hasTools()
                .toolExists("add")
                .toolExists("echo") // second page — proves cursor pagination works
                .toolNamesAreUnique()
                .toolNamesMatch("[a-z0-9_]+")
                .toolsHaveDescriptions()
                .toolSchemasAreValid()
                .toolListWithinTokenBudget(2_000)
                .eachToolWithinTokenBudget(500)
                .callToolSucceeds("add", Map.of("a", 2, "b", 3))
                .toolOutputSchemasAreValid()
                .callToolConformsToOutputSchema("add", Map.of("a", 2, "b", 3))
                .declaresResourcesCapability()
                .hasResources()
                .resourceUrisAreUnique()
                .resourcesHaveNames()
                .readResourceSucceeds("sample://greeting")
                .declaresPromptsCapability()
                .hasPrompts()
                .promptNamesAreUnique()
                .promptArgumentsAreWellFormed()
                .getPromptSucceeds("greet", Map.of("who", "world"))
                .unknownMethodYieldsMethodNotFound()
                .unknownToolHandledGracefully();
    }

    @Test
    void capturesServerInitiatedNotifications(McpTestClient client) {
        // The sample server pushes notifications/tools/list_changed right after the
        // initialized notification; listTools() gives the pump time to deliver it.
        client.listTools();
        org.junit.jupiter.api.Assertions.assertTrue(
                client.awaitNotification("notifications/tools/list_changed", java.time.Duration.ofSeconds(5)),
                "expected a tools/list_changed notification from the sample server");
    }

    @Test
    void reportsServerMetadata(McpTestClient client) {
        assertEquals("sample-mcp-server", client.serverName());
        assertEquals("1.0.0", client.serverVersion());
        assertEquals(StdioMcpTestClient.REQUESTED_PROTOCOL_VERSION, client.protocolVersion());
    }

    @Test
    void toolCallReturnsContent(McpTestClient client) {
        JsonNode result = client.callTool("add", Map.of("a", 2, "b", 3));
        assertEquals("5", result.path("content").get(0).path("text").asText());
    }

    @Test
    void unknownToolReportsError(McpTestClient client) {
        JsonNode result = client.callTool("missing", Map.of());
        assertEquals(true, result.path("isError").asBoolean());
    }

    @Test
    void answersServerPingWithAnEmptyResult(McpTestClient client) {
        // The sample server pings us right after initialization and reports back what it got.
        // The spec requires an empty result; answering -32601 lets a server treat us as a dead peer.
        client.listTools();
        org.junit.jupiter.api.Assertions.assertTrue(
                client.awaitNotification("notifications/ping_observed", java.time.Duration.ofSeconds(5)),
                "expected the sample server to report back on its ping");
        JsonNode observed = client.notifications().stream()
                .filter(n -> "notifications/ping_observed".equals(n.path("method").asText()))
                .findFirst()
                .orElseThrow()
                .path("params");
        assertEquals(true, observed.path("answeredWithResult").asBoolean(),
                "ping must be answered with an empty result, got error code "
                        + observed.path("errorCode").asInt());
    }
}
