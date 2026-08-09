package io.github.senor14.mcptestkit.e2e;

import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end test of the {@code spring:} URL scheme: {@code @SpringBootTest} starts the
 * app on a random port, and the testkit discovers that port from the Spring context —
 * no manual port wiring.
 */
@SpringBootTest(classes = SampleSpringMcpApp.class, webEnvironment = RANDOM_PORT)
@McpServerTest(url = "spring:/mcp")
class SpringMcpEndToEndTest {

    @Test
    void conformancePassAgainstSpringBootServer(McpTestClient client) {
        McpAssertions.assertThat(client)
                .initializesSuccessfully()
                .hasTools()
                .toolExists("add")
                .toolExists("echo")
                .toolsHaveDescriptions()
                .toolSchemasAreValid()
                .callToolSucceeds("add", Map.of("a", 40, "b", 2));
        assertEquals("sample-mcp-server", client.serverName());
    }
}
