package io.github.senor14.mcptestkit.junit;

import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * JUnit 5 extension behind {@link McpServerTest}: connects to the MCP server before all
 * tests in the class (launching it first for the stdio transport), injects the connected
 * {@link McpTestClient} into test parameters, and shuts everything down afterwards.
 */
public class McpTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(McpTestExtension.class);
    private static final String CLIENT_KEY = "client";

    @Override
    public void beforeAll(ExtensionContext context) {
        McpServerTest annotation = context.getRequiredTestClass().getAnnotation(McpServerTest.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    "McpTestExtension requires the test class to be annotated with @McpServerTest");
        }
        context.getStore(NAMESPACE).put(CLIENT_KEY, connect(annotation));
    }

    private static McpTestClient connect(McpServerTest annotation) {
        boolean hasCommand = annotation.command().length > 0;
        boolean hasUrl = !annotation.url().isBlank();
        if (hasCommand == hasUrl) {
            throw new IllegalStateException(
                    "@McpServerTest requires exactly one of 'command' (stdio) or 'url' (HTTP)");
        }
        Duration timeout = Duration.ofSeconds(annotation.requestTimeoutSeconds());
        if (hasCommand) {
            return StdioMcpTestClient.connect(
                    resolvePlaceholders(annotation.command()),
                    parseEntries(annotation.env(), "env"),
                    timeout);
        }
        return HttpMcpTestClient.connect(
                URI.create(resolvePlaceholder(annotation.url())),
                parseEntries(annotation.headers(), "headers"),
                timeout);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        McpTestClient client = context.getStore(NAMESPACE).get(CLIENT_KEY, McpTestClient.class);
        if (client != null) {
            client.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return McpTestClient.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        McpTestClient client = extensionContext.getStore(NAMESPACE).get(CLIENT_KEY, McpTestClient.class);
        if (client == null) {
            throw new ParameterResolutionException("No MCP test client available — check @McpServerTest setup");
        }
        return client;
    }

    /**
     * Expands {@code ${property}} placeholders from Java system properties, so annotations
     * can stay compile-time constant while referring to runtime values, e.g.
     * {@code "${java.home}/bin/java"} or {@code "http://localhost:${server.port}/mcp"}.
     * Unknown properties are left as-is.
     */
    private static String[] resolvePlaceholders(String[] values) {
        String[] resolved = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            resolved[i] = resolvePlaceholder(values[i]);
        }
        return resolved;
    }

    private static String resolvePlaceholder(String value) {
        StringBuilder result = new StringBuilder(value);
        int start;
        while ((start = result.indexOf("${")) >= 0) {
            int end = result.indexOf("}", start);
            if (end < 0) {
                break;
            }
            String property = System.getProperty(result.substring(start + 2, end));
            if (property == null) {
                break;
            }
            result.replace(start, end + 1, property);
        }
        return result.toString();
    }

    private static Map<String, String> parseEntries(String[] entries, String attribute) {
        Map<String, String> map = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(attribute + " entries must be KEY=VALUE, got: " + entry);
            }
            map.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return map;
    }
}
