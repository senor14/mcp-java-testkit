package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Minimal Spring Boot app exposing the sample MCP server ({@link SampleMcpLogic}) at
 * {@code POST /mcp}, used to test the {@code spring:} URL scheme end-to-end without
 * depending on any MCP SDK.
 */
@SpringBootApplication
@RestController
public class SampleSpringMcpApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mcp(@RequestBody String body) throws IOException {
        JsonNode message = MAPPER.readTree(body);
        ObjectNode response = SampleMcpLogic.handle(message);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(MAPPER.writeValueAsString(response));
    }
}
