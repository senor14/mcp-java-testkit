# Changelog

## 0.2.0 — 2026-08-09

- **Streamable HTTP transport** (`HttpMcpTestClient`, `@McpServerTest(url = ...)`): test MCP servers over HTTP — including Spring AI MCP servers via `@SpringBootTest(webEnvironment = RANDOM_PORT)`. Handles plain-JSON and SSE response modes, and transparently captures/echoes `Mcp-Session-Id` for servers on pre-2026 protocol revisions (including session `DELETE` on close).
- `@McpServerTest` gains `url` and `headers` attributes; `${property}` interpolation now applies to `url` as well.
- Internal: protocol flow extracted to `AbstractMcpTestClient`, shared by both transports.

## 0.1.0 — 2026-08-09

First release, available on [Maven Central](https://central.sonatype.com/artifact/io.github.senor14/mcp-java-testkit).

- `@McpServerTest` JUnit 5 extension: boots an MCP server over stdio per test class and injects a connected `McpTestClient`; command entries support `${property}` interpolation.
- SDK-independent stdio wire-protocol client (`StdioMcpTestClient`) — tests servers written with any SDK or language, requests the 2026-07-28 protocol revision.
- `McpAssertions`: initialize handshake, tool presence, description coverage, structural `inputSchema` validation, token-budget gates, tool-call success.
- `McpSnapshot`: contract snapshot testing with canonical JSON files, `-Dmcp.snapshot.update=true` regeneration.
- End-to-end tested against a real child-JVM MCP server (including `tools/list` cursor pagination); CI on Linux/Windows × JDK 17/21.
