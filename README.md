# mcp-java-testkit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.senor14/mcp-java-testkit)](https://central.sonatype.com/artifact/io.github.senor14/mcp-java-testkit)
[![CI](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Testing toolkit for MCP (Model Context Protocol) servers on the JVM.**

MCP server test tooling today is TypeScript-centric. If you build MCP servers in Java — with the official [java-sdk](https://github.com/modelcontextprotocol/java-sdk) or Spring AI — there is no native way to write conformance, contract, or regression tests in your own stack. `mcp-java-testkit` fills that gap:

- **JUnit 5 extension** — spin up your MCP server for a test class, get an injected test client, tear everything down cleanly.
- **Conformance checks** — assert your server satisfies MCP spec expectations (tool schemas are valid JSON Schema, descriptions present, initialize handshake correct — tracking the 2026-07-28 revision).
- **Contract / snapshot regression** — snapshot your tool list and schemas; fail CI when a change would break existing clients.
- **Token-budget gates** — fail CI when a tool description or response payload exceeds a configured token budget, keeping your server agent-friendly.

## Installation

```xml
<dependency>
    <groupId>io.github.senor14</groupId>
    <artifactId>mcp-java-testkit</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

```groovy
testImplementation 'io.github.senor14:mcp-java-testkit:0.1.0'
```

> Pre-1.0: minor releases may still evolve the API.

## Quick start

```java
@McpServerTest(command = {"java", "-jar", "target/my-mcp-server.jar"})
class MyServerConformanceTest {

    @Test
    void conformsToSpec(McpTestClient client) {
        McpAssertions.assertThat(client)
            .initializesSuccessfully()
            .hasTools()
            .toolsHaveDescriptions()
            .toolSchemasAreValid();
    }

    @Test
    void toolContractIsStable(McpTestClient client) {
        McpSnapshot.matches("tools", client.listTools());
    }

    @Test
    void staysWithinTokenBudget(McpTestClient client) {
        McpAssertions.assertThat(client)
            .toolListWithinTokenBudget(2_000);
    }
}
```

Works with any MCP server reachable over **stdio or Streamable HTTP** — including servers written in other languages. HTTP support means you can test a Spring AI MCP server in-process:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
class MySpringServerTest {
    // Spring sets local.server.port; the testkit resolves ${...} from system properties
    @McpServerTest(url = "http://localhost:${test.server.port}/mcp")
    // ...
}
```

The HTTP client follows the 2026-07-28 stateless revision and transparently echoes `Mcp-Session-Id` for servers on older revisions, handling both plain JSON and SSE response modes.

## Relationship to official tooling

- The official [conformance](https://github.com/modelcontextprotocol/conformance) suite validates protocol compliance as a CLI/GitHub Action. This project is the **JUnit-native layer**: it runs inside `mvn test` on every build and adds project-specific contract and regression checks a generic runner cannot know about. Use both.
- The official [java-sdk](https://github.com/modelcontextprotocol/java-sdk) publishes `mcp-test`, the shared fixtures its own integration tests use. Those are tied to the SDK and its spec revision (2025-11-25 as of java-sdk 2.0.0, Tier 2). `mcp-java-testkit` speaks the stdio wire protocol directly, so it can test servers built on **any** SDK — or any language — and check newer spec revisions (2026-07-28) before Tier-2 SDKs catch up.

## License

Apache License 2.0
