package io.github.senor14.mcptestkit.e2e;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring AI WebMVC MCP server used to retain servlet-stack regression coverage.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = McpServerStreamableHttpWebFluxAutoConfiguration.class)
public class SampleWebMvcSpringAiMcpApp {

    @Bean
    ToolCallbackProvider sampleTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new SampleTools()).build();
    }

    private static final class SampleTools {

        @Tool(description = "Returns a greeting for the supplied name.")
        String greet(String name) {
            return "Hello, " + name + "!";
        }
    }
}
