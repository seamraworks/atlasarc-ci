# AtlasArc CI

AtlasArc CI is the open-source enforcement side of AtlasArc repository cycle governance. It reads
the repository-owned `.atlasarc/governance/cycles.json`, compares those decisions with fresh JVM or
TypeScript dependency evidence, and fails when an unaccepted cycle remains.

The project is deliberately cycle-specific. It is not a general architecture-rule DSL, a metrics
quality gate, or a build runner. AtlasArc CI never invokes Maven, Gradle, npm, or
dependency-cruiser: your build produces evidence first, then AtlasArc evaluates it.

## Choose an artifact

All artifacts use the `io.atlasarc` group and are released together.

| Artifact | Use it when |
|---|---|
| `atlasarc-ci` | You need a standalone, language-neutral process and exit code in CI. |
| `atlasarc-archunit` | A JVM project already runs JUnit/ArchUnit and should enforce governance as an ordinary test. |
| `atlasarc-governance-core` | You are building an evidence or workflow adapter over the portable contract and evaluator. |

The IntelliJ plugin is optional. It provides the visual authoring and repair workflow, but the
governance file and CI engine do not require IntelliJ or a paid license at runtime.

Requirements: JDK 21 or newer, an owning Git repository, and fresh dependency evidence.

## Standalone CLI

Release builds provide an `atlasarc-ci-<version>-standalone.jar` through GitHub Releases and the
`io.atlasarc:atlasarc-ci:<version>` Maven artifact for custom launchers. Until the first release is
published, build the standalone JAR from this repository. It is the most direct CI entry point:

```shell
java -jar atlasarc-ci-1.0.0-standalone.jar evaluate \
  --config .atlasarc/evaluator.json \
  --format human
```

`repositoryRoot` is resolved relative to the configuration file and may point anywhere inside the
owning Git repository. AtlasArc locates that repository and reads `.atlasarc/governance/cycles.json`
from its root. Class, source, and artifact paths are repository-relative.

### JVM configuration

Compile the project before evaluating it. A single-module Maven project can use:

```json
{
  "$schema": "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
  "configVersion": 1,
  "repositoryRoot": "..",
  "sources": [
    {
      "id": "jvm:whole-project",
      "backend": "jvm-bytecode",
      "classDirectories": [{"path": "target/classes"}],
      "sourceRoots": [{"path": "src/main/java"}]
    }
  ]
}
```

For a multi-module build, label every class and source root with the same stable module names used
when the decisions were created:

```json
{
  "$schema": "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
  "configVersion": 1,
  "repositoryRoot": "..",
  "sources": [
    {
      "id": "jvm:whole-project",
      "backend": "jvm-bytecode",
      "classDirectories": [
        {"path": "billing/target/classes", "module": "billing"},
        {"path": "orders/target/classes", "module": "orders"}
      ],
      "sourceRoots": [
        {"path": "billing/src/main/java", "module": "billing"},
        {"path": "orders/src/main/java", "module": "orders"}
      ]
    }
  ]
}
```

Do not mix named and unlabelled JVM roots in one source. Split packages are separate architecture
units per module; an unqualified decision that matches multiple module locations fails closed.

### TypeScript configuration

Generate dependency-cruiser JSON with the project's pinned Node toolchain, then evaluate that
artifact:

```shell
npx depcruise --output-type json src > .atlasarc/depgraph.json
java -jar atlasarc-ci-1.0.0-standalone.jar evaluate \
  --backend typescript-artifact \
  --source-id typescript:frontend \
  --root . \
  --dependency-cruiser .atlasarc/depgraph.json
```

The equivalent configuration is:

```json
{
  "$schema": "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
  "configVersion": 1,
  "repositoryRoot": "..",
  "sources": [
    {
      "id": "typescript:frontend",
      "backend": "typescript-artifact",
      "root": ".",
      "dependencyCruiserJson": ".atlasarc/depgraph.json"
    }
  ]
}
```

If source files are newer than the configured bytecode or dependency-cruiser artifact, evaluation
is invalid instead of falsely clean.

### Output and exit codes

Use `--format human`, `--format json`, or `--format sarif`. Add `--output <path>` to write a file.
Machine output contains portable identities, record IDs, problem groups, and diagnostics; it omits
decision reasons, tickets, and absolute workstation paths.

| Exit | Meaning |
|---:|---|
| `0` | Governance is valid and no unaccepted cycle remains. |
| `1` | Evaluation is valid and one or more unaccepted cycles remain. |
| `2` | Configuration, schema, evidence freshness, acquisition, or governance validation failed. |
| `3` | An unexpected internal error occurred. |

Normal evaluation is read-only. AtlasArc for IntelliJ can author and repair decisions visually;
the JSON contract is also public for teams that deliberately maintain it with other tooling.

## ArchUnit and JUnit

The ArchUnit adapter puts the same evaluator into the normal JVM test lifecycle. This is useful
locally as well as in CI: accepted cycles pass, while a new unaccepted cycle fails the test.

Maven:

```xml
<dependency>
  <groupId>io.atlasarc</groupId>
  <artifactId>atlasarc-archunit</artifactId>
  <version>&lt;version&gt;</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.4.2</version>
  <scope>test</scope>
</dependency>
```

Gradle:

```kotlin
testImplementation("io.atlasarc:atlasarc-archunit:<version>")
testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
```

Java recipe for a module-less project:

```java
package architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.atlasarc.archunit.AtlasArcGovernanceRules;
import java.nio.file.Path;

@AnalyzeClasses(packages = "com.example")
class CycleGovernanceTest {
    @ArchTest
    static final ArchRule accepted_cycles_do_not_fail =
        AtlasArcGovernanceRules.governedCycles()
            .fromRepository(Path.of("."))
            .forAnalysisSource("jvm:whole-project")
            .withSourceRoot(Path.of("src/main/java"))
            .withClassRoot(Path.of("target/classes"))
            .build();
}
```

For a multi-module imported class set, call `withModuleSourceRoot(name, path)` and
`withModuleClassRoot(name, path)` for every module. The class roots make attribution deterministic
even when modules contain the same package and source-file names.

## Governance contract

The canonical file is `.atlasarc/governance/cycles.json`. A package-level JVM decision looks like:

```json
{
  "$schema": "https://atlasarc.io/schemas/cycle-governance-v1.schema.json",
  "schemaVersion": 1,
  "records": {
    "accept-order-to-billing": {
      "analysisSource": {
        "id": "jvm:whole-project",
        "backend": "jvm-bytecode",
        "language": "java"
      },
      "scope": "package",
      "ownerSide": "source",
      "source": {"architectureUnit": "com.example.orders", "module": "orders"},
      "target": {"architectureUnit": "com.example.billing", "module": "billing"},
      "referenceIds": [],
      "kind": "DEBT",
      "reason": "Existing coupling tracked for removal.",
      "ticket": "ARCH-42"
    }
  }
}
```

`INTENTIONAL` records describe accepted architecture; `DEBT` records preserve an explicit cleanup
obligation. When records overlap, `DEBT` wins. AtlasArc removes only fully governed concrete
references from the problem graph (“red wins”), then computes strongly connected components. This
ensures a broad-looking record cannot silently hide an uncovered dependency on the same edge.

The bundled schemas are authoritative:

- `atlasarc-governance-core/src/main/resources/io/atlasarc/governance/cycle-governance-v1.schema.json`
- `atlasarc-ci/src/main/resources/evaluator-config.schema.json`

## Embedding the core

Adapters can depend only on the portable engine:

```kotlin
implementation("io.atlasarc:atlasarc-governance-core:<version>")
```

Create a `GovernanceEvidenceSnapshot`, wrap it in `GovernanceEvaluationInput`, and call
`CycleGovernanceEvaluator.evaluate`. The core has no IntelliJ, ArchUnit, Node, or build-tool
dependency. Evidence adapters own acquisition; the core owns validation, matching, coverage,
cycle calculation, and the deterministic result contract.

## Build and verify

```shell
./gradlew check releaseCandidate
```

The build requires JDK 21 and produces reproducible archives. `releaseCandidate` runs all tests,
checks module boundaries, builds source and Javadoc JARs, assembles the standalone CLI bundle, and
generates checksums. CI also tests Windows because path normalization is part of the governance
contract.

## Compatibility and security

The three artifacts share one version and follow semantic versioning. Governance schema and result
versions are independent protocol versions; unsupported newer documents fail closed. Before 1.0,
minor releases may adjust public APIs. From 1.0 onward, incompatible public API or contract changes
require a major release.

Report security issues privately to `support@atlasarc.io`; do not open a public issue for an
unfixed vulnerability. See [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Runtime dependency licenses are summarized in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
and retained in the standalone distribution.
