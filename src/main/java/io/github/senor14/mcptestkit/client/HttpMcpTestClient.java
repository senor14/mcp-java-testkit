package io.github.senor14.mcptestkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP test client for the Streamable HTTP transport: each JSON-RPC message is POSTed to
 * the server's MCP endpoint, and the response arrives either as a plain
 * {@code application/json} body or as a {@code text/event-stream} of SSE events.
 * Server-initiated notifications interleaved in SSE bodies are recorded and exposed via
 * {@link #notifications()}; {@link #openNotificationStream()} additionally opens the
 * standalone GET listening stream.
 *
 * <p>Built on the JDK's {@link HttpClient} with no SDK dependency, so it can test MCP
 * servers written with any framework — including a Spring Boot MCP server started with
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}.</p>
 *
 * <p>Speaks the 2025-11-25 Streamable HTTP transport: the negotiated revision is echoed
 * on every post-handshake request via {@code MCP-Protocol-Version}, and an
 * {@code Mcp-Session-Id} issued during initialize is captured and echoed automatically.</p>
 */
public final class HttpMcpTestClient extends AbstractMcpTestClient {

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> extraHeaders;
    private final Duration timeout;

    private volatile String sessionId;
    private volatile CompletableFuture<?> listeningStream;

    private HttpMcpTestClient(URI endpoint, Map<String, String> extraHeaders, Duration timeout) {
        this.endpoint = endpoint;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Connects to the MCP endpoint and performs the initialize handshake. */
    public static HttpMcpTestClient connect(URI endpoint, Map<String, String> headers, Duration timeout) {
        HttpMcpTestClient client = new HttpMcpTestClient(endpoint, headers, timeout);
        client.initialize();
        return client;
    }

    /**
     * Opens the standalone SSE listening stream ({@code GET} on the MCP endpoint) through
     * which servers push notifications outside request/response exchanges. Notifications
     * received on it appear in {@link #notifications()}.
     */
    public HttpMcpTestClient openNotificationStream() {
        HttpRequest request = builder().GET().header("Accept", "text/event-stream").build();
        listeningStream = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(line -> {
                    String stripped = line.strip();
                    if (stripped.startsWith("data:")) {
                        recordSseData(stripped.substring(5).strip());
                    }
                }));
        return this;
    }

    @Override
    protected JsonNode performRequest(long id, String method, ObjectNode params) {
        HttpResponse<java.io.InputStream> response = post(requestEnvelope(id, method, params));
        try (java.io.InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MCP endpoint " + endpoint + " returned HTTP "
                        + response.statusCode() + " for " + method + ": " + readAll(body));
            }
            captureSessionId(response);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.startsWith("text/event-stream")) {
                // The spec says servers SHOULD terminate the stream after the response —
                // some (java-sdk streamable with keep-alives) keep it open instead, so the
                // stream must be consumed event by event and left as soon as the response
                // arrives. Reading to EOF here hangs forever on such servers.
                return readSseUntilResponse(body, id, method);
            }
            JsonNode message = MAPPER.readTree(readAll(body));
            return message;
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid response from MCP endpoint " + endpoint
                    + " for " + method, e);
        }
    }

    @Override
    protected void sendNotification(String method) {
        HttpResponse<java.io.InputStream> response = post(notificationEnvelope(method));
        // Close without draining: a server may answer a notification POST with an
        // open SSE stream, and reading it to EOF would block.
        try (java.io.InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MCP endpoint " + endpoint + " returned HTTP "
                        + response.statusCode() + " for notification " + method);
            }
        } catch (IOException ignored) {
            // Closing an already-broken stream is not a test failure.
        }
    }

    /**
     * Consumes an SSE body line by line, answering interleaved server requests and
     * recording notifications, and returns as soon as the response with {@code id}
     * arrives — the stream is then closed by the caller, open or not. The wait is bounded
     * by the client timeout independently of whether the server keeps sending bytes: lines
     * are read on a separate thread and polled with the remaining time, so a stream that
     * goes completely silent fails the test with a message instead of hanging the build
     * (the request-level timeout only covers response headers, not body streaming).
     */
    private JsonNode readSseUntilResponse(java.io.InputStream body, long id, String method) throws IOException {
        SseLines lines = SseLines.start(body);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            SseLines.Item item = remaining > 0 ? lines.poll(remaining) : null;
            if (item == null) {
                throw new IllegalStateException("SSE stream from " + endpoint + " exceeded the "
                        + timeout.toSeconds() + "s timeout without a response to " + method);
            }
            if (item.error != null) {
                throw item.error;
            }
            if (item.line == null) {
                throw new IllegalStateException("SSE stream from " + endpoint
                        + " ended without a response to " + method);
            }
            String stripped = item.line.strip();
            if (!stripped.startsWith("data:")) {
                continue;
            }
            JsonNode message = recordSseData(stripped.substring(5).strip());
            if (message != null && message.path("id").asLong(-1) == id && !message.has("method")) {
                return message;
            }
        }
    }

    /**
     * Reads an SSE body on a daemon thread and hands lines over through a queue, so the
     * caller can wait with a deadline. Closing the body (which the caller does on every
     * exit path) ends the reader.
     */
    private static final class SseLines {
        static final class Item {
            final String line;      // null = end of stream
            final IOException error; // non-null = read failed

            Item(String line, IOException error) {
                this.line = line;
                this.error = error;
            }
        }

        private final java.util.concurrent.BlockingQueue<Item> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();

        static SseLines start(java.io.InputStream body) {
            SseLines lines = new SseLines();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8));
            Thread reader0 = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.queue.offer(new Item(line, null));
                    }
                    lines.queue.offer(new Item(null, null));
                } catch (IOException e) {
                    lines.queue.offer(new Item(null, e));
                }
            }, "mcp-testkit-sse-reader");
            reader0.setDaemon(true);
            reader0.start();
            return lines;
        }

        Item poll(long nanos) {
            try {
                return queue.poll(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static String readAll(java.io.InputStream body) throws IOException {
        return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (listeningStream != null) {
            listeningStream.cancel(true);
        }
        if (sessionId != null) {
            // Older-revision servers keep session state; politely terminate it. The wait is
            // bounded by the client timeout: the request-level timeout covers headers only,
            // and a server that answers DELETE with a body it never ends must not hang close().
            CompletableFuture<HttpResponse<Void>> delete =
                    http.sendAsync(builder().DELETE().build(), HttpResponse.BodyHandlers.discarding());
            try {
                delete.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                delete.cancel(true);
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // A failed or hanging session DELETE is not a test failure.
                delete.cancel(true);
            }
        }
    }

    private HttpResponse<java.io.InputStream> post(JsonNode message) {
        try {
            HttpRequest request = builder()
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(message)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to POST to MCP endpoint " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling MCP endpoint " + endpoint, e);
        }
    }

    private HttpRequest.Builder builder() {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            requestBuilder.header("Mcp-Session-Id", sessionId);
        }
        // Required on every request after initialize since the 2025-06-18 revision; servers
        // that never see it fall back to assuming 2025-03-26.
        String negotiated = protocolVersion();
        if (!negotiated.isEmpty()) {
            requestBuilder.header("MCP-Protocol-Version", negotiated);
        }
        extraHeaders.forEach(requestBuilder::header);
        return requestBuilder;
    }

    private void captureSessionId(HttpResponse<?> response) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
    }

    /**
     * Parses one SSE data payload. Notifications are recorded; server-initiated requests are
     * answered. Returns the message.
     */
    private JsonNode recordSseData(String data) {
        JsonNode message;
        try {
            message = MAPPER.readTree(data);
        } catch (IOException e) {
            return null; // Ignore malformed SSE payloads.
        }
        if (message.has("method")) {
            if (message.has("id")) {
                answerServerRequest(message);
            } else {
                recordNotification(message);
            }
        }
        return message;
    }

    /**
     * Answers a server-initiated request by POSTing the JSON-RPC response back to the endpoint.
     * {@code ping} must be answered with an empty result — staying silent is what makes a server
     * conclude the peer is dead — while sampling, elicitation and roots are refused politely.
     */
    private void answerServerRequest(JsonNode requestMessage) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", requestMessage.get("id"));
        if ("ping".equals(requestMessage.path("method").asText())) {
            response.putObject("result");
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", "mcp-java-testkit does not serve " + requestMessage.path("method").asText());
        }
        try {
            HttpRequest request = builder()
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(response)))
                    .build();
            // Fire and forget: this may run on the SSE reading thread, which must not block.
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to answer server request from " + endpoint, e);
        }
    }
}
