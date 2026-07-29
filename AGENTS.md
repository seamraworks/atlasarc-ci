# AtlasArc.io CI contributor instructions

This public repository is the canonical source for AtlasArc.io's portable cycle-governance contract,
standalone CI evaluator, JUnit adapter, and ArchUnit adapter.

## Boundaries

- `atlasarc-governance-core` owns the schema, repository store, portable evidence contract,
  matcher, accepted-reference overlay, SCC evaluation, and deterministic result. It must not
  depend on IntelliJ, ArchUnit, Node, Gradle APIs, plugin view models, or product workflows.
- `atlasarc-archunit` maps imported JVM bytecode to portable evidence and exposes the native
  ArchRule. It depends inward on core.
- `atlasarc-ci` owns configuration, headless acquisition, output renderers, exit codes, and the
  executable distribution. It reuses the ArchUnit JVM evidence adapter and depends on core for all
  governance semantics.
- `atlasarc-junit` exposes the configured evaluator as a JUnit 5 assertion. It depends on
  `atlasarc-ci`, performs no separate acquisition or evaluation, and must preserve the evaluator's
  JVM, TypeScript, and mixed-stack behavior.
- The private AtlasArc.io for IntelliJ repository consumes these public coordinates. Never introduce
  a dependency on private plugin code or copy plugin workflows into this repository.

Native enforcement is cycle-only. Do not add metric gates, an architecture-rule DSL, Safe Havens,
IDE presentation, or general governance-file mutation as incidental changes. Normal evaluation is
read-only.

## Verification

Use JDK 21 or newer and run:

```text
mvn verify -Ppublish-cli
```

Changes to schemas, stable IDs, matching, module attribution, red-wins behavior, output contracts,
or exit codes require deterministic regression coverage. Keep Java, Kotlin, TypeScript,
multi-module split-package, stale-evidence, and invalid-record paths fail-closed.

Use semantic commits in the form `action(subject): summary`; do not add sign-off trailers.

## Releases

Before changing any version or approving a CircleCI gate, read `RELEASING.md`. This repository
releases the public AtlasArc.io CI Maven artifacts, not the separately versioned IntelliJ plugin.
If the desired reactor version is already committed, use the as-is approval gate. Maven Central,
the Git tag, and the GitHub Release are distinct verification steps.
