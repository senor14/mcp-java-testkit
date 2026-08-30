package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void parsesSseEventStreamResponsesAndRecordsInterleavedNotifications() throws Exception {
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(true, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            JsonNode result = client.callTool("add", Map.of("a", 2, "b", 3));
            assertEquals("5", result.path("content").get(0).path("text").asText());
            assertTrue(client.awaitNotification("notifications/tools/list_changed", TIMEOUT),
                    "notifications interleaved in SSE response bodies should be recorded");
        }
    }

    @Test
    void listeningStreamDeliversStandaloneNotifications() throws Exception {
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            client.openNotificationStream();
            assertTrue(client.awaitNotification("notifications/tools/list_changed", TIMEOUT),
                    "the GET listening stream should deliver server-initiated notifications");
        }
    }

    @Test
    void returnsOnTheResponseEventEvenWhenTheStreamStaysOpen() throws Exception {
        // The spec says servers SHOULD terminate the SSE stream after the response; some
        // keep it open with keep-alives instead. The client must return when the response
        // event arrives, not when the stream ends — reading to EOF here means hanging until
        // the server gives up (observed: a java-sdk streamable server holding a test for hours).
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(true, false, 4_000);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            long start = System.nanoTime();
            client.listTools();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 2_000,
                    "should return on the response event while the stream is still open; took "
                            + elapsedMs + " ms against a 4s hold");
        }
    }

    @Test
    void failsWithinTheTimeoutWhenTheStreamGoesSilent() throws Exception {
        // A server that sends the response headers and then nothing at all — no response,
        // no keep-alives. The deadline must fire on its own; a reader that only checks it
        // after a line arrives would block for as long as the server holds the socket.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(true, false, 0, 6_000, 0);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure.getMessage().contains("exceeded the 1s timeout"),
                    "should report the timeout, got: " + failure.getMessage());
            assertTrue(elapsedMs < 3_000,
                    "should fail at the 1s deadline, not when the server closes after 6s; took "
                            + elapsedMs + " ms");
        }
    }

    @Test
    void failsWithinTheTimeoutWhenAJsonBodyGoesSilent() throws Exception {
        // Same stall on the plain-JSON path. readAllBytes() has no deadline of its own and
        // the request-level timeout covers headers only, so the client must bound the read.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, false, 0, 6_000, 0);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure.getMessage().contains("exceeded the 1s timeout"),
                    "should report the timeout, got: " + failure.getMessage());
            assertTrue(elapsedMs < 3_000,
                    "should fail at the 1s deadline, not when the server closes after 6s; took "
                            + elapsedMs + " ms");
        }
    }

    @Test
    void keepsTheStatusCodeWhenAnErrorBodyGoesSilent() throws Exception {
        // A 500 whose body never arrives: the failure must still say "HTTP 500" — a stalled
        // error body reported only as a slow body sends the user debugging the wrong thing.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(
                     new SampleHttpMcpServer.Options().errorStatus(500).silentStallMillis(6_000));
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure.getMessage().contains("returned HTTP 500"),
                    "should keep the status code, got: " + failure.getMessage());
            assertTrue(failure.getCause() != null && failure.getCause().getMessage().contains("exceeded the 1s timeout"),
                    "should keep the body-read failure as the cause, got: " + failure.getCause());
            assertTrue(elapsedMs < 3_000, "should fail at the 1s deadline; took " + elapsedMs + " ms");
        }
    }

    @Test
    void boundsHeadersAndBodyWithOneClock() throws Exception {
        // Headers arrive late, then the body stalls. One timeout must cover the whole
        // exchange: with a fresh clock per phase this would take headers + timeout.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(
                     new SampleHttpMcpServer.Options().headerDelayMillis(2_000).silentStallMillis(6_000));
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), Duration.ofSeconds(3))) {
            long start = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure.getMessage().contains("exceeded the 3s timeout"),
                    "should report the timeout, got: " + failure.getMessage());
            assertTrue(elapsedMs < 4_500,
                    "should fail ~3s after the request was sent, not 2s + 3s; took " + elapsedMs + " ms");
        }
    }

    @Test
    void failsLoudlyOnAnEmptyJsonBody() throws Exception {
        // An empty 200 used to parse to a missing node, and listTools() returned nothing.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(new SampleHttpMcpServer.Options().emptyBody(true));
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
            assertTrue(failure.getMessage().contains("returned an empty body"),
                    "should fail on the empty body, got: " + failure.getMessage());
        }
    }

    @Test
    void closeReturnsWithinTheTimeoutWhenTheSessionDeleteNeverEnds() throws Exception {
        // The session DELETE is answered with headers and a body that never ends. The
        // request-level timeout covers headers only, so close() must bound the wait itself.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, true, 0, 0, 6_000)) {
            HttpMcpTestClient client = HttpMcpTestClient.connect(
                    URI.create(server.endpoint()), Map.of(), Duration.ofSeconds(1));
            long start = System.nanoTime();
            client.close();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(server.sessionWasDeleted(), "close() should still attempt the DELETE");
            assertTrue(elapsedMs < 3_000, "close() should give up at the 1s timeout; took " + elapsedMs + " ms");
        }
    }

    @Test
    void closeRestoresTheInterruptFlagWhenInterrupted() throws Exception {
        // Catching InterruptedException clears the flag; close() must set it again so the
        // caller (a test runner shutting down, say) still sees the interrupt.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, true, 0, 0, 6_000)) {
            HttpMcpTestClient client = HttpMcpTestClient.connect(
                    URI.create(server.endpoint()), Map.of(), TIMEOUT);
            java.util.concurrent.atomic.AtomicBoolean flagAfterClose = new java.util.concurrent.atomic.AtomicBoolean();
            Thread closer = new Thread(() -> {
                client.close();
                flagAfterClose.set(Thread.currentThread().isInterrupted());
            });
            closer.start();
            Thread.sleep(300); // let close() block on the hanging DELETE
            closer.interrupt();
            closer.join(5_000);
            assertTrue(!closer.isAlive(), "close() should return once interrupted");
            assertTrue(flagAfterClose.get(), "close() must re-set the interrupt flag it consumed");
        }
    }

    @Test
    void answersServerPingOverHttpWithAnEmptyResult() throws Exception {
        // The spec requires the receiver of a ping to answer with an empty result. Staying silent
        // is what makes a server conclude the peer is dead, so an unanswered ping is worse than an
        // error reply.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(true, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            client.listTools(); // the SSE body for this carries a server-initiated ping
            assertTrue(server.awaitPingAnswer(TIMEOUT), "the client should answer a server ping");
            assertTrue(server.pingAnsweredWithResult(), "ping must be answered with a result, not an error");
        }
    }

    @Test
    void sendsNegotiatedProtocolVersionHeaderAfterHandshake() throws Exception {
        // Required on every post-initialize request since the 2025-06-18 revision; without it a
        // server is entitled to assume 2025-03-26.
        try (SampleHttpMcpServer server = new SampleHttpMcpServer(false, false);
             HttpMcpTestClient client = HttpMcpTestClient.connect(
                     URI.create(server.endpoint()), Map.of(), TIMEOUT)) {
            client.listTools();
            assertEquals(client.protocolVersion(), server.lastProtocolVersionHeader());
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
