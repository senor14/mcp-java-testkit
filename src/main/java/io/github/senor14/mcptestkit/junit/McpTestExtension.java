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
                annotation.command(),
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
