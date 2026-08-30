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
    /** After writing the SSE response, hold the stream open this long (0 = close immediately). */
    private final long keepStreamOpenMillis;
    /**
     * For post-handshake requests in SSE mode: send the response headers and then nothing
     * at all — no response, no keep-alives — for this long before closing (0 = off).
     */
    private final long silentStallMillis;
    /** Answer session {@code DELETE} with headers and then hold the body open this long (0 = off). */
    private final long hangDeleteMillis;
    /** Delay the response headers of post-handshake requests this long (0 = off). */
    private final long headerDelayMillis;
    /** Answer post-handshake requests with this HTTP status and a body that stalls (0 = off). */
    private final int errorStatus;
    /** Answer post-handshake JSON requests with a 200 and no body at all. */
    private final boolean emptyBody;
    /** Id of the server-initiated ping interleaved into SSE responses. */
    static final String PING_ID = "server-ping-http";

    private final AtomicBoolean sessionDeleted = new AtomicBoolean();
    private final AtomicBoolean pingAnsweredWithResult = new AtomicBoolean();
    private final CountDownLatch pingAnswered = new CountDownLatch(1);
    private volatile String sessionId;
    private volatile String lastProtocolVersionHeader;

    /** Misbehaviour knobs; every mode leaves the initialize handshake untouched. */
    static final class Options {
        boolean sse;
        boolean withSession;
        long keepStreamOpenMillis;
        long silentStallMillis;
        long hangDeleteMillis;
        long headerDelayMillis;
        int errorStatus;
        boolean emptyBody;

        Options sse(boolean value) { sse = value; return this; }
        Options withSession(boolean value) { withSession = value; return this; }
        Options keepStreamOpenMillis(long value) { keepStreamOpenMillis = value; return this; }
        Options silentStallMillis(long value) { silentStallMillis = value; return this; }
        Options hangDeleteMillis(long value) { hangDeleteMillis = value; return this; }
        Options headerDelayMillis(long value) { headerDelayMillis = value; return this; }
        Options errorStatus(int value) { errorStatus = value; return this; }
        Options emptyBody(boolean value) { emptyBody = value; return this; }
    }

    SampleHttpMcpServer(boolean sse, boolean withSession) throws IOException {
        this(sse, withSession, 0);
    }

    SampleHttpMcpServer(boolean sse, boolean withSession, long keepStreamOpenMillis) throws IOException {
        this(sse, withSession, keepStreamOpenMillis, 0, 0);
    }

    SampleHttpMcpServer(boolean sse, boolean withSession, long keepStreamOpenMillis,
                        long silentStallMillis, long hangDeleteMillis) throws IOException {
        this(new Options().sse(sse).withSession(withSession).keepStreamOpenMillis(keepStreamOpenMillis)
                .silentStallMillis(silentStallMillis).hangDeleteMillis(hangDeleteMillis));
    }

    SampleHttpMcpServer(Options options) throws IOException {
        this.sse = options.sse;
        this.withSession = options.withSession;
        this.keepStreamOpenMillis = options.keepStreamOpenMillis;
        this.silentStallMillis = options.silentStallMillis;
        this.hangDeleteMillis = options.hangDeleteMillis;
        this.headerDelayMillis = options.headerDelayMillis;
        this.errorStatus = options.errorStatus;
        this.emptyBody = options.emptyBody;
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
        boolean handedOff = false;
        try {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                sessionDeleted.set(true);
                if (hangDeleteMillis > 0) {
                    // Headers go out, the body never ends: a header-only timeout cannot
                    // bound this, so close() must bound it itself.
                    exchange.sendResponseHeaders(200, 0);
                    handedOff = true;
                    holdOpen(exchange, hangDeleteMillis, false);
                    return;
                }
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
            // These knobs target post-handshake requests only; the initialized notification
            // that completes the handshake must still be answered normally.
            boolean postHandshakeRequest = !isInitialize && message.has("id");
            if (postHandshakeRequest && headerDelayMillis > 0) {
                try {
                    Thread.sleep(headerDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while delaying headers", e);
                }
            }
            if (postHandshakeRequest && errorStatus > 0) {
                // An error status whose body then stalls: the status must survive in the
                // client's failure message even though the body never arrives.
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(errorStatus, 0);
                exchange.getResponseBody().flush();
                handedOff = true;
                holdOpen(exchange, silentStallMillis, false);
                return;
            }
            if (postHandshakeRequest && emptyBody) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, -1);
                return;
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
                String sseBody = interleaved + "event: message\ndata: " + json + "\n\n";
                if (silentStallMillis > 0 && !isInitialize) {
                    // Headers only, then silence: no response event, no keep-alive
                    // comments. A reader that only checks its deadline after a line
                    // arrives can never time out here.
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    handedOff = true;
                    holdOpen(exchange, silentStallMillis, false);
                    return;
                }
                if (keepStreamOpenMillis > 0 && !isInitialize) {
                    // The spec says servers SHOULD close the stream after the response;
                    // this mode imitates the ones that keep it open with keep-alives
                    // instead (java-sdk streamable), so the client's return must come
                    // from the response event, not from stream EOF.
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    out.write(sseBody.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    handedOff = true;
                    holdOpen(exchange, keepStreamOpenMillis, true);
                    return;
                }
                respond(exchange, 200, "text/event-stream", sseBody);
            } else {
                if (silentStallMillis > 0 && !isInitialize) {
                    // Same stall on the plain-JSON path: headers, then nothing.
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    handedOff = true;
                    holdOpen(exchange, silentStallMillis, false);
                    return;
                }
                respond(exchange, 200, "application/json", json);
            }
        } finally {
            if (!handedOff) {
                exchange.close();
            }
        }
    }

    /**
     * Keeps a chunked response body open for {@code millis} on a daemon thread, writing SSE
     * keep-alive comments every 250ms when {@code keepAlives} is set and nothing at all
     * otherwise, then closes the exchange.
     */
    private static void holdOpen(HttpExchange exchange, long millis, boolean keepAlives) {
        OutputStream out = exchange.getResponseBody();
        Thread holder = new Thread(() -> {
            try {
                long end = System.currentTimeMillis() + millis;
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(250);
                    if (keepAlives) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
                // client closed the connection first — expected
            } finally {
                exchange.close();
            }
        });
        holder.setDaemon(true);
        holder.start();
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
