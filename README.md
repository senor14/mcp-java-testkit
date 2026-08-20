# mcp-java-testkit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.senor14/mcp-java-testkit)](https://central.sonatype.com/artifact/io.github.senor14/mcp-java-testkit)
[![CI](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Testing toolkit for MCP (Model Context Protocol) servers on the JVM.**

Most MCP test tooling runs *against* your server from the outside — the official inspector, conformance CLIs, scanners like mcp-observatory. `mcp-java-testkit` brings this in-process on the JVM: SDK-independent, wire-level assertions that live in your own JUnit suite, run on every `mvn test`, and fail the build when your protocol surface changes:

- **JUnit 5 extension** — spin up your MCP server for a test class, get an injected test client, tear everything down cleanly.
- **Conformance checks** — 26 fluent assertions across the initialize handshake, capabilities, tools (schemas, naming, structured output), resources, prompts, and error paths (tracking the 2026-07-28 revision).
- **Contract / snapshot regression** — snapshot your tool list and schemas; fail CI when a change would break existing clients.
- **Token-budget gates** — fail CI when a tool list or an individual tool exceeds a configured token budget, keeping your server agent-friendly.
- **Notification capture** — server-initiated notifications are recorded on every transport, including the standalone HTTP GET listening stream.

**Protocol coverage**: initialize/capabilities, tools (list + pagination, call, input/output schemas, structured content), resources (list, templates, read), prompts (list, get), server notifications, and error-path behavior — over stdio and Streamable HTTP (JSON + SSE + session compatibility). Not yet covered: client-served requests (sampling/elicitation are auto-rejected), completions, and OAuth flows.

## Installation

```xml
<dependency>
    <groupId>io.github.senor14</groupId>
    <artifactId>mcp-java-testkit</artifactId>
    <version>0.4.0</version>
    <scope>test</scope>
</dependency>
```

```groovy
testImplementation 'io.github.senor14:mcp-java-testkit:0.4.0'
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

Works with any MCP server reachable over **stdio or Streamable HTTP** — including servers written in other languages. Spring AI MCP servers get first-class support via the `spring:` URL scheme, which discovers the random test port from the Spring context automatically:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@McpServerTest(url = "spring:/mcp")
class MySpringServerTest {
    @Test
    void conformsToSpec(McpTestClient client) {
        McpAssertions.assertThat(client).initializesSuccessfully().toolSchemasAreValid();
    }
}
```

No Spring dependency is pulled in — the port lookup is reflective and only activates when you use `spring:`. The HTTP client follows the 2026-07-28 stateless revision and transparently echoes `Mcp-Session-Id` for servers on older revisions, handling both plain JSON and SSE response modes.

## Relationship to official tooling

- The official [conformance](https://github.com/modelcontextprotocol/conformance) suite validates protocol compliance as a CLI/GitHub Action. This project is the **JUnit-native layer**: it runs inside `mvn test` on every build and adds project-specific contract and regression checks a generic runner cannot know about. Use both.
- The official [java-sdk](https://github.com/modelcontextprotocol/java-sdk) publishes `mcp-test`, the shared fixtures its own integration tests use. Those are tied to the SDK and its spec revision — java-sdk 2.0.1 still targets the 2025-11-25 spec (as of Aug 2026). `mcp-java-testkit` speaks the wire protocol directly, so it can test servers built on **any** SDK — or any language — and already checks the 2026-07-28 revision.

## License

Apache License 2.0
