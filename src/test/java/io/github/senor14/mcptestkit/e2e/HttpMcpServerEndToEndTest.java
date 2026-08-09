package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Streamable HTTP transport against a real local HTTP server,
 * covering plain-JSON responses, SSE responses, and pre-2026 session-id handling.
 */
class HttpMcpServerEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void fullConformancePassOverPlainJson() throws Exception {
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            McpAssertions.assertThat(client)
                    .initializesSuccessfully()
                    .hasTools()
                    .toolExists("add")
                    .toolExists("echo") // second page — proves cursor pagination over HTTP
                    .toolsHaveDescriptions()
                    .toolSchemasAreValid()
                    .toolListWithinTokenBudget(2_000)
                    .callToolSucceeds("add", Map.of("a", 20, "b", 22));
            assertEquals("sample-mcp-server", client.serverName());
        }
    }

    @Test
    void parsesSseEventStreamResponses() throws Exception {
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(true, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            JsonNode result = client.callTool("add", Map.of("a", 2, "b", 3));
            assertEquals("5", result.path("content").get(0).path("text").asText());
        }
    }

    @Test
    void capturesAndEchoesSessionIdForOlderRevisions() throws Exception {
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, true)) {
            HttpMcpTestClient client = HttpMcpTestClient.connect(
                    URI.create(server.endpoint()), Map.of(), TIMEOUT);
            // Requests after initialize succeed only if the session id was echoed correctly.
            McpAssertions.assertThat(client)
                    .hasTools()
                    .callToolSucceeds("echo", Map.of("text", "hi"));
            client.close();
            assertTrue(server.sessionWasDeleted(), "close() should DELETE the session");
        }
    }
}
