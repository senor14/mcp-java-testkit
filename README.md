# mcp-java-testkit

**Testing toolkit for MCP (Model Context Protocol) servers on the JVM.**

MCP server test tooling today is TypeScript-centric. If you build MCP servers in Java — with the official [java-sdk](https://github.com/modelcontextprotocol/java-sdk) or Spring AI — there is no native way to write conformance, contract, or regression tests in your own stack. `mcp-java-testkit` fills that gap:

- **JUnit 5 extension** — spin up your MCP server for a test class, get an injected test client, tear everything down cleanly.
- **Conformance checks** — assert your server satisfies MCP spec expectations (tool schemas are valid JSON Schema, descriptions present, initialize handshake correct — tracking the 2026-07-28 revision).
- **Contract / snapshot regression** — snapshot your tool list and schemas; fail CI when a change would break existing clients.
- **Token-budget gates** — fail CI when a tool description or response payload exceeds a configured token budget, keeping your server agent-friendly.

> Status: early development (v0). API surface may change until 0.1.0 is published to Maven Central.

## Quick look (target API)

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

Works with any MCP server reachable over stdio — including servers written in other languages — and integrates with Spring Boot Test for in-process testing of Spring AI MCP servers (planned module: `mcp-java-testkit-spring`).

## Relationship to official tooling

- The official [conformance](https://github.com/modelcontextprotocol/conformance) suite validates protocol compliance as a CLI/GitHub Action. This project is the **JUnit-native layer**: it runs inside `mvn test` on every build and adds project-specific contract and regression checks a generic runner cannot know about. Use both.
- The official [java-sdk](https://github.com/modelcontextprotocol/java-sdk) publishes `mcp-test`, the shared fixtures its own integration tests use. Those are tied to the SDK and its spec revision (2025-11-25 as of java-sdk 2.0.0, Tier 2). `mcp-java-testkit` speaks the stdio wire protocol directly, so it can test servers built on **any** SDK — or any language — and check newer spec revisions (2026-07-28) before Tier-2 SDKs catch up.

## License

Apache License 2.0
