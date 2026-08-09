package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A minimal, SDK-agnostic view of a connected MCP client, scoped to what tests need.
 *
 * <p>Implementations wrap a real MCP connection over some transport and are injected into
 * test methods by {@link McpServerTest}. Keeping this interface free of SDK types means
 * test code survives SDK breaking changes.</p>
 */
public interface McpTestClient extends AutoCloseable {

    /** Whether the initialize handshake completed successfully. */
    boolean initialized();

    /** Server name reported during initialization, or {@code ""} if unavailable. */
    String serverName();

    /** Server version reported during initialization, or {@code ""} if unavailable. */
    String serverVersion();

    /** Negotiated MCP protocol version, or {@code ""} if unavailable. */
    String protocolVersion();

    /**
     * The {@code capabilities} object the server declared during initialization,
     * or an empty object if unavailable.
     */
    JsonNode serverCapabilities();

    /**
     * The tool list as returned by the server (all pages), one JSON object per tool with
     * at least {@code name}, {@code description}, and {@code inputSchema} fields when present.
     */
    List<JsonNode> listTools();

    /** Convenience accessor for the names of all tools. */
    List<String> toolNames();

    /**
     * Calls a tool and returns the result content as JSON:
     * {@code {"isError": bool, "content": [...], "structuredContent": {...}?}}.
     */
    JsonNode callTool(String name, Map<String, Object> arguments);

    /** The resource list as returned by the server (all pages). */
    List<JsonNode> listResources();

    /** The resource template list as returned by the server. */
    List<JsonNode> listResourceTemplates();

    /** Reads a resource and returns the result: {@code {"contents": [...]}}. */
    JsonNode readResource(String uri);

    /** The prompt list as returned by the server (all pages). */
    List<JsonNode> listPrompts();

    /** Gets a prompt and returns the result: {@code {"description"?, "messages": [...]}}. */
    JsonNode getPrompt(String name, Map<String, Object> arguments);

    /**
     * Sends a raw JSON-RPC request and returns the <em>full response envelope</em>
     * ({@code result} or {@code error} included, nothing unwrapped) — the door for
     * error-path conformance checks.
     */
    JsonNode exchange(String method, JsonNode params);

    /**
     * Server-initiated notifications observed so far, in arrival order. For HTTP
     * transports, notifications arrive interleaved in SSE response bodies and on the
     * listening stream (see {@code HttpMcpTestClient#openNotificationStream()}).
     */
    List<JsonNode> notifications();

    /** Polls {@link #notifications()} until one with the given method arrives, or the timeout elapses. */
    default boolean awaitNotification(String method, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean seen = notifications().stream()
                    .anyMatch(notification -> method.equals(notification.path("method").asText()));
            if (seen) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    void close();
}
