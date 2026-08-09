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

/**
 * MCP test client for the Streamable HTTP transport: each JSON-RPC message is POSTed to
 * the server's MCP endpoint, and the response arrives either as a plain
 * {@code application/json} body or as a {@code text/event-stream} of SSE events.
 *
 * <p>Built on the JDK's {@link HttpClient} with no SDK dependency, so it can test MCP
 * servers written with any framework — including Spring AI MCP servers started with
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}.</p>
 *
 * <p>Follows the 2026-07-28 (stateless) revision. For servers on older revisions that
 * issue an {@code Mcp-Session-Id} during initialize, the header is captured and echoed
 * on subsequent requests automatically.</p>
 */
public final class HttpMcpTestClient extends AbstractMcpTestClient {

    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, String> extraHeaders;
    private final Duration timeout;

    private volatile String sessionId;

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

    @Override
    protected JsonNode request(String method, ObjectNode params) {
        long id = idSequence.incrementAndGet();
        HttpResponse<String> response = post(requestEnvelope(id, method, params));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("MCP endpoint " + endpoint + " returned HTTP "
                    + response.statusCode() + " for " + method + ": " + response.body());
        }
        captureSessionId(response);
        JsonNode message = extractResponseMessage(response, id, method);
        return unwrapResponse(message, method);
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        extraHeaders.forEach(builder::header);
        return builder;
    }

    private void captureSessionId(HttpResponse<String> response) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
    }

    /**
     * Extracts the JSON-RPC response with the given id from either a plain JSON body or
     * an SSE stream body ({@code data:} lines, possibly interleaved with server-initiated
     * notifications, which are skipped).
     */
    private JsonNode extractResponseMessage(HttpResponse<String> response, long id, String method) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (contentType.startsWith("text/event-stream")) {
                for (String line : response.body().split("\n")) {
                    line = line.strip();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    JsonNode message = MAPPER.readTree(line.substring(5).strip());
                    if (message.path("id").asLong(-1) == id && !message.has("method")) {
                        return message;
                    }
                }
                throw new IllegalStateException("SSE stream from " + endpoint
                        + " ended without a response to " + method);
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid JSON from MCP endpoint " + endpoint
                    + " for " + method + ": " + response.body(), e);
        }
    }
}
