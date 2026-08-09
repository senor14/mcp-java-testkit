package io.github.senor14.mcptestkit.junit;

import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * JUnit 5 extension behind {@link McpServerTest}: launches the server process before all
 * tests in the class, injects the connected {@link McpTestClient} into test parameters,
 * and shuts everything down afterwards.
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
        McpTestClient client = StdioMcpTestClient.connect(
                resolvePlaceholders(annotation.command()),
                parseEnv(annotation.env()),
                Duration.ofSeconds(annotation.requestTimeoutSeconds()));
        context.getStore(NAMESPACE).put(CLIENT_KEY, client);
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
     * Expands {@code ${property}} placeholders in command entries from Java system
     * properties, so annotations can stay compile-time constant while referring to
     * runtime paths, e.g. {@code "${java.home}/bin/java"} or {@code "${java.class.path}"}.
     * Unknown properties are left as-is.
     */
    private static String[] resolvePlaceholders(String[] command) {
        String[] resolved = new String[command.length];
        for (int i = 0; i < command.length; i++) {
            StringBuilder value = new StringBuilder(command[i]);
            int start;
            while ((start = value.indexOf("${")) >= 0) {
                int end = value.indexOf("}", start);
                if (end < 0) {
                    break;
                }
                String property = System.getProperty(value.substring(start + 2, end));
                if (property == null) {
                    break;
                }
                value.replace(start, end + 1, property);
            }
            resolved[i] = value.toString();
        }
        return resolved;
    }

    private static Map<String, String> parseEnv(String[] entries) {
        Map<String, String> env = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("env entries must be KEY=VALUE, got: " + entry);
            }
            env.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return env;
    }
}
