# Gradle packaged-JAR example

A tiny Java stdio MCP server and one JUnit conformance test that launches its
executable JAR. Requires JDK 17+ with `java` on `PATH`; no Gradle or Maven installation
is needed. The checked-in wrapper downloads Gradle 9.7.1 and verifies its SHA-256
checksum. Dependencies come from Maven Central, including the released testkit 0.7.0;
you do not need to build or install the parent project first.

From this directory on Linux/macOS:

```sh
./gradlew test
```

On Windows (PowerShell):

```powershell
.\gradlew.bat test
```

Use `clean test` to repeat from an empty build directory. The test report is at
`build/reports/tests/test/index.html`.

The important wiring in [build.gradle](build.gradle) is:

```groovy
tasks.test {
    dependsOn tasks.jar
    useJUnitPlatform()
}
```

The `jar` task sets `Main-Class` and bundles Jackson into
`build/libs/gradle-packaged-jar.jar`. `PackagedJarConformanceTest` uses
`@McpServerTest(command = {"java", "-jar", "build/libs/gradle-packaged-jar.jar"})`
to start that artifact over stdio. Gradle runs tests from the project directory,
so the relative JAR path works on Linux and Windows. Without `dependsOn tasks.jar`,
`test` compiles the server but does not package it, and a clean run fails to launch it.

For a Spring Boot project, depend on `tasks.bootJar` instead and point the annotation
at that task's executable JAR. This example uses the plain Java plugin to keep the
packaging dependency visible.
