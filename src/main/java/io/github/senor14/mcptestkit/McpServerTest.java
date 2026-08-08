package io.github.senor14.mcptestkit;

import io.github.senor14.mcptestkit.junit.McpTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots an MCP server over stdio for the annotated test class and injects a
 * {@link McpTestClient} into test method parameters.
 *
 * <pre>{@code
 * @McpServerTest(command = {"java", "-jar", "target/my-server.jar"})
 * class MyServerConformanceTest {
 *     @Test
 *     void conformsToSpec(McpTestClient client) {
 *         McpAssertions.assertThat(client).initializesSuccessfully();
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(McpTestExtension.class)
public @interface McpServerTest {

    /** Command line used to launch the server process, e.g. {@code {"npx", "-y", "my-server"}}. */
    String[] command();

    /** Extra environment variables for the server process, each entry as {@code "KEY=VALUE"}. */
    String[] env() default {};

    /** Per-request timeout in seconds. */
    long requestTimeoutSeconds() default 30;
}
