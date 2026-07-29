# Run AtlasArc.io CI from JUnit

The `atlasarc-junit` adapter runs the configured AtlasArc.io evaluator in-process and turns its
verdict into an ordinary JUnit 5 assertion. It is the direct test-lifecycle path for Java, Kotlin,
TypeScript, and mixed repositories. It does not launch the standalone JAR and does not require the
test to use ArchUnit annotations or an `ArchRule`.

The configured evaluator still uses ArchUnit's importer internally when it acquires JVM bytecode.
The difference is ownership: this adapter lets evaluator configuration own acquisition, while the
native ArchUnit adapter starts with classes already imported by the consuming test suite.

## Add the adapter

`io.atlasarc:atlasarc-junit:1.4.0` is published on Maven Central. Add it in the consuming project's
test scope.

Maven:

```xml
<dependency>
  <groupId>io.atlasarc</groupId>
  <artifactId>atlasarc-junit</artifactId>
  <version>1.4.0</version>
  <scope>test</scope>
</dependency>
```

Gradle:

```kotlin
testImplementation("io.atlasarc:atlasarc-junit:1.4.0")
```

## Produce current evidence first

The assertion reads `.atlasarc/evaluator.json`, so it has the same evidence contract as the
standalone CLI:

- compile the configured Java/Kotlin class directories before the test;
- generate configured dependency-cruiser JSON after the TypeScript source is current; and
- include both source kinds in one config when one JUnit test should own the mixed-stack verdict.

AtlasArc.io CI does not run Maven, Gradle, npm, or dependency-cruiser. ESLint and SonarJS artifacts
are not inputs to this cycle-governance evaluator.

For Maven, an ordinary test phase already follows main compilation. TypeScript generation may be
bound to `generate-test-resources` or another phase before `test`. In Gradle, make the JUnit test
task depend on the task that generates dependency-cruiser JSON.

## Assert repository governance

With the conventional config path:

```java
package architecture;

import io.atlasarc.junit.AtlasArcGovernanceAssertions;
import org.junit.jupiter.api.Test;

final class CycleGovernanceTest {
    @Test
    void repositoryCycleGovernance() {
        AtlasArcGovernanceAssertions.assertGovernance();
    }
}
```

Or pass an explicit config path:

```java
AtlasArcGovernanceAssertions.assertGovernance(
    Path.of(".atlasarc/evaluator.json")
);
```

The two-argument overload additionally accepts the current directory used to resolve a relative
config path. This is useful when a multi-module test runs with a module directory rather than the
Git root as its working directory.

## Read the result

The adapter preserves the evaluator's fail-closed contract:

| Evaluator outcome | JUnit behavior |
|---|---|
| Clean | The assertion returns normally. |
| Unaccepted cycle | The assertion fails with the human cycle summary. |
| Invalid configuration, governance, scope, or stale evidence | The assertion fails as invalid; it never reports a false clean result. |
| Internal evaluator error | The assertion fails with the available evaluator diagnostic. |

The assertion is read-only. It never runs the cycle-debt baseline and never writes
`.atlasarc/governance/cycles.json`.

Use the [standalone evaluator](evaluator-configuration.md) when a pipeline needs process exit codes,
JSON, or SARIF. Use the `atlasarc-archunit` adapter when an existing Java/Kotlin architecture suite
already owns class acquisition through ArchUnit and wants AtlasArc as a native `ArchRule`.
