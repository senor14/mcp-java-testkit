package io.github.senor14.mcptestkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MCP test client that speaks the stdio wire protocol (newline-delimited JSON-RPC 2.0)
 * directly, with no MCP SDK dependency.
 *
 * <p>Talking to the wire instead of an SDK is deliberate: conformance tests should observe
 * what the server actually sends, and the toolkit can test MCP servers written in any
 * language or SDK version.</p>
 */
public final class StdioMcpTestClient extends AbstractMcpTestClient {

    private final Process process;
    private final BufferedWriter serverStdin;
    private final Duration timeout;
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private final StringBuilder stderrBuffer = new StringBuilder();

    private StdioMcpTestClient(Process process, Duration timeout) {
        this.process = process;
        this.timeout = timeout;
        this.serverStdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startPump("mcp-testkit-stdout", process.inputReader(StandardCharsets.UTF_8), this::onServerLine);
        startPump("mcp-testkit-stderr", process.errorReader(StandardCharsets.UTF_8),
                line -> stderrBuffer.append(line).append('\n'));
    }

    /** Launches the server process and performs the MCP initialize handshake. */
    public static StdioMcpTestClient connect(String[] command, Map<String, String> env, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to launch MCP server: " + String.join(" ", command), e);
        }
        StdioMcpTestClient client = new StdioMcpTestClient(process, timeout);
        client.initialize();
        return client;
    }

    @Override
    public void close() {
        try {
            serverStdin.close();
        } catch (IOException ignored) {
            // Server may have exited already; process teardown below is what matters.
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    protected JsonNode request(String method, ObjectNode params) {
        long id = idSequence.incrementAndGet();
        send(requestEnvelope(id, method, params));

        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException(timeoutMessage(method));
            }
            JsonNode received;
            try {
                received = incoming.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for MCP response to " + method, e);
            }
            if (received == null) {
                throw new IllegalStateException(timeoutMessage(method));
            }
            if (received.path("id").asLong(-1) == id && !received.has("method")) {
                return unwrapResponse(received, method);
            }
            // Anything else is a server notification or request; handled in onServerLine.
        }
    }

    @Override
    protected void sendNotification(String method) {
        send(notificationEnvelope(method));
    }

    private void onServerLine(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (IOException e) {
            return; // Non-JSON noise on stdout; conformance checks for this can come later.
        }
        boolean isServerRequest = message.has("method") && message.has("id");
        if (isServerRequest) {
            rejectServerRequest(message);
            return;
        }
        if (message.has("id")) {
            incoming.offer(message);
        }
        // Notifications are ignored in v0.
    }

    /** Tests don't serve sampling/elicitation; refuse politely so the server never blocks on us. */
    private void rejectServerRequest(JsonNode requestMessage) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", requestMessage.get("id"));
        ObjectNode error = response.putObject("error");
        error.put("code", -32601);
        error.put("message", "mcp-java-testkit does not serve " + requestMessage.path("method").asText());
        send(response);
    }

    private synchronized void send(JsonNode message) {
        try {
            serverStdin.write(MAPPER.writeValueAsString(message));
            serverStdin.write('\n');
            serverStdin.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write to MCP server stdin. Server stderr so far:\n"
                    + stderrBuffer, e);
        }
    }

    private void startPump(String threadName, BufferedReader reader, java.util.function.Consumer<String> onLine) {
        Thread pump = new Thread(() -> {
            try (reader) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // Stream closed on shutdown.
            }
        }, threadName);
        pump.setDaemon(true);
        pump.start();
    }

    private String timeoutMessage(String method) {
        return "Timed out after " + timeout + " waiting for MCP response to '" + method
                + "'. Server alive: " + process.isAlive() + ". Server stderr:\n" + stderrBuffer;
    }
}
