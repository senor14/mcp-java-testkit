# Contributing

Thanks for your interest! Issues and pull requests are welcome.

## Development

- JDK 17+ and Maven (`mvn -B test` runs the full suite, including end-to-end tests
  against real child-process and HTTP sample servers — no external services needed).
- Follow the existing code style; keep the published artifact dependency-free beyond
  Jackson (Spring support is reflective on purpose).

## Reporting protocol gaps

The most valuable issues are of the form "the toolkit can't test X behavior of the MCP
spec" — please include the spec revision and section. Current known gaps are listed in
the README's protocol-coverage note.

## Pull requests

- Add or extend tests for every change (assertions are unit-tested against the fake
  client and covered end-to-end where transport behavior is involved).
- One logical change per PR.
- CI must be green on Linux/Windows × JDK 17/21.
