# AI maintenance log

This project is maintained by a solo maintainer with AI assistance. This log discloses
where AI tooling materially contributed, per entry. Every AI-assisted change is reviewed,
run, and verified by the maintainer before it lands; entries link to the public
commit / PR / issue / release they refer to.

Tools used to date: **Claude Code** (Anthropic — Opus/Sonnet/Haiku models, per-task tiering).

| Date | Tool | Work | Public artifact |
|---|---|---|---|
| 2026-08-09 | Claude Code | Initial toolkit implementation and release pipeline: JUnit 5 extension, stdio transport, assertions, snapshot regression, token-budget gates; Maven Central publishing setup | [v0.1.0](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.1.0) |
| 2026-08-09 | Claude Code | Streamable HTTP transport (JSON + SSE, `Mcp-Session-Id` compatibility), `spring:` URL scheme for `@SpringBootTest` random-port discovery | [v0.2.0](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.2.0), [v0.3.0](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.3.0) |
| 2026-08-09 | Claude Code | Protocol-surface completion: resources, prompts, structured output, error paths, notification capture (incl. HTTP GET listening stream) — 26 assertions, 36 tests | [v0.4.0](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.4.0) |
| 2026-08-09 | Claude Code | Drafted Republic of Korea holiday data (statutory + substitute-holiday entries per year) for upstream jollyday; maintainer review feedback addressed | [jollyday#1456](https://github.com/focus-shift/jollyday/pull/1456) |
| 2026-08-09 | Claude Code | Wrote an MCP wire-protocol conformance integration test for maven-tools-mcp using this toolkit — first downstream adoption (merged 2026-08-19) | [maven-tools-mcp#14](https://github.com/arvindand/maven-tools-mcp/pull/14) |
| 2026-08-12 | Claude Code | Reproduced and reported: documented JAR invocation never responds over stdio outside the docker profile (fixed by maintainer) | [maven-tools-mcp#15](https://github.com/arvindand/maven-tools-mcp/issues/15) |
| 2026-08-12 | Claude Code | Measured tool-list token cost of sonarqube-mcp-server with this toolkit's token-budget assertions; reported with per-tool breakdown and follow-up corrections | [sonarqube-mcp-server#527](https://github.com/SonarSource/sonarqube-mcp-server/issues/527) |
| 2026-08-13 | Claude Code | Diagnosed Streamable HTTP transport providers advertising 2024-11-05 during version negotiation; drafted upstream fix + tests | [java-sdk#1088](https://github.com/modelcontextprotocol/java-sdk/pull/1088) |
| 2026-08-20 | Claude Code | Verified McpSnapshot behavior against 0.3.0 (3 reproduction tests) before answering a downstream question on the merged PR | [maven-tools-mcp#14 comment](https://github.com/arvindand/maven-tools-mcp/pull/14#issuecomment-5352689017) |
| 2026-08-20 | Claude Code | README repositioning (in-process framing, dated java-sdk spec facts, version bumps) and this log | [612f3a7](https://github.com/senor14/mcp-java-testkit/commit/612f3a7), [fc2556c](https://github.com/senor14/mcp-java-testkit/commit/fc2556c) |
| 2026-08-20 | Claude Code | Audited this project's own public claims against the MCP spec text; found and fixed two conformance defects in the client (server `ping` answered with `-32601`; missing `MCP-Protocol-Version` header) and corrected the spec-revision and Spring AI claims in the docs | [v0.5.0](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.5.0) |
| 2026-08-20 | Claude Code | Re-audit of the 0.5.0 fixes caught that `ping` handling had landed on stdio only; implemented server-request answering for the Streamable HTTP transport, with a test verified to fail without the fix | [v0.5.1](https://github.com/senor14/mcp-java-testkit/releases/tag/v0.5.1) |
