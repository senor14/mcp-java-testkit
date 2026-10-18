package io.github.senor14.mcptestkit.e2e;

import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Verifies that {@code spring:} discovers a random Spring AI WebMVC server port.
 */
@SpringBootTest(
        classes = SampleWebMvcSpringAiMcpApp.class,
        webEnvironment = RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=servlet",
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.server.name=sample-webmvc-mcp-server",
                "spring.ai.mcp.server.version=1.0.0"
        })
@McpServerTest(url = "spring:/mcp")
class WebMvcSpringAiMcpEndToEndTest {

    @Test
    void conformancePassesAgainstSpringAiWebMvcServer(McpTestClient client) {
        McpAssertions.assertThat(client)
                .initializesSuccessfully()
                .hasTools()
                .toolsHaveDescriptions()
                .toolSchemasAreValid();
    }
}
