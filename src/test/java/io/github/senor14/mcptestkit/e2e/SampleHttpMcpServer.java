package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Streamable HTTP MCP server for end-to-end tests, built on the JDK's
 * {@link HttpServer}. Behavior lives in {@link SampleMcpLogic}; this class only handles
 * transport concerns, configurable per instance:
 *
 * <ul>
 *   <li>{@code sse} — respond with {@code text/event-stream} instead of plain JSON</li>
 *   <li>{@code withSession} — issue an {@code Mcp-Session-Id} on initialize and require
 *       it on every later request (pre-2026 revision behavior)</li>
 * </ul>
 */
final class SampleHttpMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final boolean sse;
    private final boolean withSession;
    /** Id of the server-initiated ping interleaved into SSE responses. */
    static final String PING_ID = "server-ping-http";

    private final AtomicBoolean sessionDeleted = new AtomicBoolean();
    private final AtomicBoolean pingAnsweredWithResult = new AtomicBoolean();
    private final CountDownLatch pingAnswered = new CountDownLatch(1);
    private volatile String sessionId;
    private volatile String lastProtocolVersionHeader;

    SampleHttpMcpServer(boolean sse, boolean withSession) throws IOException {
        this.sse = sse;
        this.withSession = withSession;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handleExchange);
        this.server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    boolean sessionWasDeleted() {
        return sessionDeleted.get();
    }

    /** The {@code MCP-Protocol-Version} header seen on the most recent post-initialize request. */
    String lastProtocolVersionHeader() {
        return lastProtocolVersionHeader;
    }

    /** Waits for the client to answer the server-initiated ping. */
    boolean awaitPingAnswer(Duration timeout) throws InterruptedException {
        return pingAnswered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Whether that answer carried a {@code result} (as the spec requires) rather than an error. */
    boolean pingAnsweredWithResult() {
        return pingAnsweredWithResult.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                sessionDeleted.set(true);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                // Standalone listening stream: push one notification, then close.
                respond(exchange, 200, "text/event-stream", "event: message\ndata: "
                        + MAPPER.writeValueAsString(SampleMcpLogic.listChangedNotification()) + "\n\n");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode message = MAPPER.readTree(exchange.getRequestBody());
            if (!message.has("method") && message.has("id")) {
                // The client answering a request we initiated.
                if (PING_ID.equals(message.path("id").asText())) {
                    pingAnsweredWithResult.set(message.path("result").isObject());
                    pingAnswered.countDown();
                }
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            boolean isInitialize = "initialize".equals(message.path("method").asText());
            if (!isInitialize) {
                lastProtocolVersionHeader = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            }
            if (withSession && !isInitialize) {
                String presented = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                if (sessionId == null || !sessionId.equals(presented)) {
                    respond(exchange, 400, "application/json",
                            "{\"error\": \"missing or wrong Mcp-Session-Id\"}");
                    return;
                }
            }
            ObjectNode response = SampleMcpLogic.handle(message);
            if (response == null) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (withSession && isInitialize) {
                sessionId = UUID.randomUUID().toString();
                exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            }
            String json = MAPPER.writeValueAsString(response);
            if (sse) {
                // Interleave a notification and, after the handshake, a server-initiated ping
                // before the response, so notification capture and request answering are both
                // exercised on the SSE path.
                String interleaved = "event: message\ndata: "
                        + MAPPER.writeValueAsString(SampleMcpLogic.listChangedNotification()) + "\n\n";
                if (!isInitialize) {
                    ObjectNode ping = MAPPER.createObjectNode();
                    ping.put("jsonrpc", "2.0");
                    ping.put("id", PING_ID);
                    ping.put("method", "ping");
                    interleaved += "event: message\ndata: " + MAPPER.writeValueAsString(ping) + "\n\n";
                }
                respond(exchange, 200, "text/event-stream",
                        interleaved + "event: message\ndata: " + json + "\n\n");
            } else {
                respond(exchange, 200, "application/json", json);
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
