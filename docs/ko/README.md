# mcp-java-testkit

[English README](../../README.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.senor14/mcp-java-testkit)](https://central.sonatype.com/artifact/io.github.senor14/mcp-java-testkit)
[![CI](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml/badge.svg)](https://github.com/senor14/mcp-java-testkit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](../../LICENSE)

**JVM에서 MCP(Model Context Protocol) 서버를 테스트하기 위한 도구 모음입니다.**

대부분의 MCP 테스트 도구는 공식 인스펙터, 적합성 검사 CLI, mcp-observatory 같은 스캐너처럼 서버 외부에서 동작합니다. `mcp-java-testkit`은 이를 JVM 내부에서 수행합니다. SDK에 종속되지 않는 와이어 수준 검증을 자체 JUnit 테스트에 포함하고, 빌드에서 실행하며, 프로토콜 인터페이스가 바뀌면 빌드를 실패시킬 수 있습니다.

- **JUnit 5 확장 기능** — 테스트 클래스마다 MCP 서버를 실행하고, 주입된 테스트 클라이언트를 제공하며, 종료 시 리소스를 정리합니다.
- **적합성 검사** — 초기화 핸드셰이크, 기능(capabilities), 도구(스키마, 이름, 구조화된 출력), 리소스, 프롬프트, 오류 경로에 대한 26개의 유연한 단언을 제공합니다(2025-11-25 개정판).
- **계약/스냅샷 회귀 검사** — 도구 목록과 스키마를 스냅샷으로 저장하고, 기존 클라이언트와의 호환성을 깨뜨릴 변경이 있으면 CI를 실패시킵니다.
- **토큰 예산 제한** — 도구 목록이나 개별 도구가 설정한 토큰 예산을 초과하면 CI를 실패시켜 서버가 에이전트에 적합한 크기를 유지하도록 합니다.
- **알림 수집** — 독립형 HTTP GET 수신 스트림을 포함해 모든 전송 방식에서 서버가 시작한 알림을 기록합니다.

**프로토콜 범위**: 초기화/기능, 도구(목록 및 페이지네이션, 호출, 입력/출력 스키마, 구조화된 콘텐츠), 리소스(목록, 템플릿, 읽기), 프롬프트(목록, 가져오기), 서버 알림, 오류 동작을 stdio 및 Streamable HTTP(JSON + SSE + 세션 호환성)로 검증합니다. 아직 지원하지 않는 항목: 클라이언트가 제공하는 요청(샘플링/사용자 입력 요청은 자동 거부), 자동 완성, OAuth 흐름.

## 설치

```xml
<dependency>
    <groupId>io.github.senor14</groupId>
    <artifactId>mcp-java-testkit</artifactId>
    <version>0.7.0</version>
    <scope>test</scope>
</dependency>
```

```groovy
testImplementation 'io.github.senor14:mcp-java-testkit:0.7.0'
```

```kotlin
testImplementation("io.github.senor14:mcp-java-testkit:0.7.0")
```

> 1.0 이전 버전이므로 마이너 릴리스에서 API가 변경될 수 있습니다.

## 빠른 시작

```java
// *IT: runs under failsafe (mvn verify) — the jar only exists after the package phase, which runs after surefire
@McpServerTest(command = {"java", "-jar", "target/my-mcp-server.jar"})
class MyServerConformanceIT {

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

`command` 방식은 블랙박스 테스트입니다. 지정한 대상을 자식 프로세스로 실행하고 stdin/stdout을 통해 와이어 프로토콜로 통신하므로 시작 인자, 패키징, stdio 처리를 모두 검증합니다. 패키징된 jar는 빌드가 끝난 뒤에야 생성됩니다. Maven에서는 `package` 단계가 surefire의 `test` 단계 뒤에 실행되므로 `target/*.jar`를 실행하는 테스트는 failsafe 아래에 두어야 합니다(`*IT`, `mvn verify`). `maven-failsafe-plugin`은 pom에 직접 연결해야 하며 기본 라이프사이클에 포함되지 않습니다. surefire는 `*IT` 클래스를 조용히 건너뜁니다. Gradle에서는 테스트 태스크가 jar를 생성하는 태스크(`jar` 또는 `bootJar`)에 의존하도록 설정하세요. 기본 `test` 단계에서 실행하려면 아래의 Spring Boot `spring:` 모드를 사용하거나, jar 없이 테스트 클래스패스에서 서버를 직접 실행할 수 있습니다.

```java
command = {"${java.home}/bin/java", "-cp", "${java.class.path}", "com.example.MyServerMain"}
```

`${...}`는 시스템 속성으로 확장됩니다. 이 저장소의 자체 엔드투엔드 테스트도 이 방식을 사용합니다.

Kotlin 테스트에서도 동일한 JUnit 5 확장 기능을 사용합니다.

```kotlin
// Gradle: make the test task depend on the task that builds this jar
@McpServerTest(command = ["java", "-jar", "build/libs/my-mcp-server.jar"])
class MyServerConformanceTest {
    @Test
    fun `server conforms to the protocol`(client: McpTestClient) {
        McpAssertions.assertThat(client)
            .initializesSuccessfully()
            .hasTools()
            .toolSchemasAreValid()
    }
}
```

**stdio 또는 Streamable HTTP**로 연결할 수 있는 모든 MCP 서버와 함께 사용할 수 있으며, 다른 언어로 작성된 서버도 포함됩니다. Spring Boot MCP 서버는 `spring:` URL 스킴을 기본 지원하며, Spring 컨텍스트에서 임의 포트를 자동으로 찾습니다.

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

Spring 의존성은 추가되지 않습니다. 포트 조회는 리플렉션을 사용하며 `spring:`을 사용할 때만 활성화됩니다. 실제 Spring AI MCP 서버(`spring-ai-starter-mcp-server-webmvc`)와 이 저장소의 샘플 서버를 대상으로 검증합니다. HTTP 클라이언트는 2025-11-25 Streamable HTTP 전송을 구현합니다. 핸드셰이크 이후 모든 요청에 협상된 버전을 `MCP-Protocol-Version`으로 전달하고, 서버가 발급한 `Mcp-Session-Id`를 저장해 다시 보내며, 일반 JSON과 SSE 응답 모드를 모두 처리합니다.

## 공식 도구와의 관계

- 공식 [conformance](https://github.com/modelcontextprotocol/conformance) 제품군은 CLI/GitHub Action으로 프로토콜 준수 여부를 확인합니다. 이 프로젝트는 **JUnit 네이티브 계층**으로, 변경이 있을 때마다 자체 빌드 안에서 실행하며 일반 검사기가 알 수 없는 프로젝트별 계약 및 회귀 검사도 추가합니다. 두 도구를 함께 사용하세요.
- 공식 [java-sdk](https://github.com/modelcontextprotocol/java-sdk)는 자체 통합 테스트에서 사용하는 공용 픽스처인 `mcp-test`를 제공합니다. 이는 SDK 자체 테스트를 위한 것이어서 테스트 코드가 해당 SDK에 종속됩니다. `mcp-java-testkit`은 와이어 프로토콜을 직접 사용하므로 어떤 SDK로 만든 서버든, 또는 어떤 언어로 만든 서버든 실제 클라이언트가 받는 내용을 검증할 수 있습니다.

### 사양 개정판

클라이언트는 **2025-11-25** 와이어 프로토콜을 구현하며 초기화 요청에서 해당 개정판을 요청합니다. 서버가 협상한 버전은 `negotiatedProtocolVersionIsOneOf(...)`로 확인할 수 있습니다.

[2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28) 개정판에서는 초기화 핸드셰이크가 `server/discover` 및 `_meta` 버전 정보로 대체되고, `Mcp-Session-Id`가 제거되며, GET 수신 스트림은 `subscriptions/listen`으로 대체됩니다. 이는 별도의 클라이언트이며 아직 구현되지 않았습니다. 이 프로젝트의 다음 주요 작업입니다.

## 개발

프로젝트는 AI 지원을 받는 1인 유지관리자가 관리합니다. AI 도구의 기여 내역은 릴리스 및 기여별로 [docs/ai-maintenance-log.md](../../docs/ai-maintenance-log.md)에 기록됩니다.

## 라이선스

Apache License 2.0
