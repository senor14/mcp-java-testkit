package io.github.senor14.mcptestkit;

import io.github.senor14.mcptestkit.junit.McpTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Connects to an MCP server for the annotated test class and injects a
 * {@link McpTestClient} into test method parameters.
 *
 * <p>Exactly one of {@link #command()} (stdio transport) or {@link #url()} (Streamable
 * HTTP transport) must be set. Values support {@code ${property}} interpolation from
 * Java system properties, e.g. {@code "${java.home}/bin/java"} or
 * {@code "http://localhost:${server.port}/mcp"}.</p>
 *
 * <pre>{@code
 * // stdio: launch the server as a child process
 * @McpServerTest(command = {"java", "-jar", "target/my-server.jar"})
 *
 * // Streamable HTTP: connect to a running server, e.g. a Spring Boot test instance
 * @McpServerTest(url = "http://localhost:${test.server.port}/mcp")
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(McpTestExtension.class)
public @interface McpServerTest {

    /** Command line used to launch a stdio server process, e.g. {@code {"npx", "-y", "my-server"}}. */
    String[] command() default {};

    /** Streamable HTTP endpoint of a running MCP server, e.g. {@code "http://localhost:8080/mcp"}. */
    String url() default "";

    /** Extra environment variables for the stdio server process, each entry as {@code "KEY=VALUE"}. */
    String[] env() default {};

    /** Extra HTTP headers for the HTTP transport, each entry as {@code "Name=value"}. */
    String[] headers() default {};

    /** Per-request timeout in seconds. */
    long requestTimeoutSeconds() default 30;
}
