package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A minimal, SDK-agnostic view of a connected MCP client, scoped to what tests need.
 *
 * <p>Implementations wrap a real MCP client (the official java-sdk by default) and are
 * injected into test methods by {@link McpServerTest}. Keeping this interface free of
 * SDK types means test code survives SDK breaking changes.</p>
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
     * The tool list as returned by the server, one JSON object per tool with at least
     * {@code name}, {@code description}, and {@code inputSchema} fields when present.
     */
    List<JsonNode> listTools();

    /** Convenience accessor for the names of all tools. */
    List<String> toolNames();

    /**
     * Calls a tool and returns the result content as JSON:
     * {@code {"isError": bool, "content": [...]}}.
     */
    JsonNode callTool(String name, Map<String, Object> arguments);

    @Override
    void close();
}
