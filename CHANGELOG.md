# Changelog

## 0.4.0 — 2026-08-09

Closes the protocol-coverage gaps beyond tools:

- **Resources & prompts**: `listResources()`, `listResourceTemplates()`, `readResource(uri)`, `listPrompts()`, `getPrompt(name, args)` on the client, with assertions `declaresResourcesCapability()`, `hasResources()`, `resourceUrisAreUnique()`, `resourcesHaveNames()`, `readResourceSucceeds(uri)`, `declaresPromptsCapability()`, `hasPrompts()`, `promptNamesAreUnique()`, `promptArgumentsAreWellFormed()`, `getPromptSucceeds(name, args)`.
- **Structured tool output**: `toolOutputSchemasAreValid()` and `callToolConformsToOutputSchema(name, args)` validate declared `outputSchema`s and that `structuredContent` conforms (required properties, declared types).
- **Error-path conformance**: `exchange(method, params)` exposes raw JSON-RPC envelopes; `unknownMethodYieldsMethodNotFound()` and `unknownToolHandledGracefully()` verify servers fail loudly, not silently.
- **Notification capture**: server-initiated notifications are recorded on all transports (`notifications()`, `awaitNotification(method, timeout)`); `HttpMcpTestClient.openNotificationStream()` opens the standalone GET SSE listening stream.

## 0.3.0 — 2026-08-09

- **Five new conformance assertions**: `declaresToolsCapability()`, `toolNamesAreUnique()`, `toolNamesMatch(regex)`, `negotiatedProtocolVersionIsOneOf(...)`, and `eachToolWithinTokenBudget(n)` (pinpoints the offending tool). `McpTestClient` now exposes `serverCapabilities()`.
- **`spring:` URL scheme** — `@McpServerTest(url = "spring:/mcp")` discovers the random port of a `@SpringBootTest(webEnvironment = RANDOM_PORT)` context automatically. The lookup is reflective, so the published artifact still has zero Spring dependencies.
- Client connection is now lazy (first parameter injection instead of `beforeAll`), making the extension independent of extension registration order.

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
