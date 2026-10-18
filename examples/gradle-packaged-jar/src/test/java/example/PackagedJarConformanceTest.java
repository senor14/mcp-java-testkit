package example;

import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@McpServerTest(command = {"java", "-jar", "build/libs/gradle-packaged-jar.jar"}, requestTimeoutSeconds = 10)
class PackagedJarConformanceTest {

    @Test
    void packagedServerConforms(McpTestClient client) {
        McpAssertions.assertThat(client)
                .initializesSuccessfully()
                .negotiatedProtocolVersionIsOneOf("2025-11-25")
                .declaresToolsCapability()
                .hasTools()
                .toolsHaveDescriptions()
                .toolSchemasAreValid()
                .callToolSucceeds("greet", Map.of())
                .unknownMethodYieldsMethodNotFound()
                .unknownToolHandledGracefully();

        assertEquals("Hello from a packaged JAR!",
                client.callTool("greet", Map.of()).path("content").get(0).path("text").asText());
    }
}
