package io.github.senor14.mcptestkit.junit;

import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * JUnit 5 extension behind {@link McpServerTest}: connects to the MCP server on first
 * use (launching it first for the stdio transport), injects the connected
 * {@link McpTestClient} into test parameters, and shuts everything down after the class.
 *
 * <p>Connecting lazily — at parameter resolution rather than {@code beforeAll} — lets the
 * {@code spring:} URL scheme observe a fully started Spring Boot test context regardless
 * of extension registration order.</p>
 */
public class McpTestExtension implements AfterAllCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(McpTestExtension.class);
    private static final String CLIENT_KEY = "client";
    private static final String SPRING_SCHEME = "spring:";

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
        ExtensionContext classContext = classContext(extensionContext);
        return classContext.getStore(NAMESPACE).getOrComputeIfAbsent(
                CLIENT_KEY,
                key -> connect(annotation(classContext), classContext),
                McpTestClient.class);
    }

    private static ExtensionContext classContext(ExtensionContext context) {
        ExtensionContext current = context;
        while (current.getParent().isPresent() && current.getTestClass().isPresent()
                && current.getParent().get().getTestClass().equals(current.getTestClass())) {
            current = current.getParent().get();
        }
        return current;
    }

    private static McpServerTest annotation(ExtensionContext context) {
        McpServerTest annotation = context.getRequiredTestClass().getAnnotation(McpServerTest.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    "McpTestExtension requires the test class to be annotated with @McpServerTest");
        }
        return annotation;
    }

    private static McpTestClient connect(McpServerTest annotation, ExtensionContext context) {
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
                URI.create(resolveUrl(annotation.url(), context)),
                parseEntries(annotation.headers(), "headers"),
                timeout);
    }

    /**
     * Resolves the annotation's {@code url} value. A {@code spring:} prefix means "the
     * Spring Boot test server started by {@code @SpringBootTest(webEnvironment = RANDOM_PORT)},
     * at the given path": the port is read from the Spring test context, discovered
     * reflectively so the toolkit needs no Spring dependency.
     */
    private static String resolveUrl(String url, ExtensionContext context) {
        if (url.startsWith(SPRING_SCHEME)) {
            String path = url.substring(SPRING_SCHEME.length());
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return "http://localhost:" + springLocalServerPort(context) + path;
        }
        return resolvePlaceholder(url);
    }

    private static String springLocalServerPort(ExtensionContext context) {
        try {
            Class<?> springExtension = Class.forName(
                    "org.springframework.test.context.junit.jupiter.SpringExtension");
            Object applicationContext = springExtension
                    .getMethod("getApplicationContext", ExtensionContext.class)
                    .invoke(null, context);
            Object environment = applicationContext.getClass()
                    .getMethod("getEnvironment")
                    .invoke(applicationContext);
            Object port = environment.getClass()
                    .getMethod("getProperty", String.class)
                    .invoke(environment, "local.server.port");
            if (port == null) {
                throw new IllegalStateException("Spring context has no 'local.server.port' — use "
                        + "@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)");
            }
            return port.toString();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("@McpServerTest(url = \"spring:...\") requires spring-test "
                    + "and @SpringBootTest on the classpath/test class", e);
        }
    }

    /**
     * Expands {@code ${property}} placeholders from Java system properties, so annotations
     * can stay compile-time constant while referring to runtime values, e.g.
     * {@code "${java.home}/bin/java"}. Unknown properties are left as-is.
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
