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
        HttpResponse<String> response = post(requestEnvelope(id, method, params));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("MCP endpoint " + endpoint + " returned HTTP "
                    + response.statusCode() + " for " + method + ": " + response.body());
        }
        captureSessionId(response);
        return extractResponseMessage(response, id, method);
    }

    @Override
    protected void sendNotification(String method) {
        HttpResponse<String> response = post(notificationEnvelope(method));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("MCP endpoint " + endpoint + " returned HTTP "
                    + response.statusCode() + " for notification " + method);
        }
    }

    @Override
    public void close() {
        if (listeningStream != null) {
            listeningStream.cancel(true);
        }
        if (sessionId != null) {
            try {
                // Older-revision servers keep session state; politely terminate it.
                http.send(builder().DELETE().build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException ignored) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private HttpResponse<String> post(JsonNode message) {
        try {
            HttpRequest request = builder()
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(message)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
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

    private void captureSessionId(HttpResponse<String> response) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
    }

    /**
     * Extracts the JSON-RPC response with the given id from either a plain JSON body or
     * an SSE stream body ({@code data:} lines). Server-initiated notifications
     * interleaved in the stream are recorded, not dropped.
     */
    private JsonNode extractResponseMessage(HttpResponse<String> response, long id, String method) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (contentType.startsWith("text/event-stream")) {
                JsonNode matched = null;
                for (String line : response.body().split("\n")) {
                    line = line.strip();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    JsonNode message = recordSseData(line.substring(5).strip());
                    if (message != null && message.path("id").asLong(-1) == id && !message.has("method")) {
                        matched = message;
                    }
                }
                if (matched == null) {
                    throw new IllegalStateException("SSE stream from " + endpoint
                            + " ended without a response to " + method);
                }
                return matched;
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid JSON from MCP endpoint " + endpoint
                    + " for " + method + ": " + response.body(), e);
        }
    }

    /** Parses one SSE data payload, recording it if it is a notification. Returns the message. */
    private JsonNode recordSseData(String data) {
        JsonNode message;
        try {
            message = MAPPER.readTree(data);
        } catch (IOException e) {
            return null; // Ignore malformed SSE payloads.
        }
        if (message.has("method") && !message.has("id")) {
            recordNotification(message);
        }
        return message;
    }
}
