# Configure the AtlasArc.io CI evaluator

The evaluator configuration tells AtlasArc.io CI where to obtain **current dependency evidence**.
It does not contain acceptance decisions. Those live separately in the repository's
`.atlasarc/governance/cycles.json` file; see [Govern cycle decisions](governance-decisions.md).
It also does not contain repository exclusions. Those live at
`.atlasarc/governance/scope.json`; see [Define repository analysis scope](repository-scope.md).

The conventional location is `.atlasarc/evaluator.json`, although `--config` accepts any path. A
committed configuration gives developers and CI the same evidence boundary and avoids a pipeline
that silently evaluates a different part of the repository.

## Evaluation sequence

For every configured source, the evaluator:

1. locates the owning Git repository;
2. loads and validates `.atlasarc/governance/scope.json` and `.atlasarc/governance/cycles.json`;
3. acquires dependency evidence from the configured JVM output or TypeScript artifact;
4. rejects missing, stale, ambiguous, or inconsistent evidence;
5. removes repository-scoped architecture units and their incident dependencies;
6. evaluates all retained sources with the same governance engine; and
7. emits a human, JSON, or SARIF result and a process exit code.

The evaluator never invokes a compiler, Maven, Gradle, npm, or dependency-cruiser. Produce the
artifacts first, then run the gate.

## Minimal configuration

This file describes a conventional single-module Maven project. Because the file lives in
`.atlasarc`, `repositoryRoot` starts the Git-root search one directory above it:

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

Run it after compilation:

```shell
mvn compile
java -jar <path-to>/atlasarc-ci-<version>-standalone.jar evaluate \
  --config .atlasarc/evaluator.json \
  --format human
```

## Top-level fields

| Field | Required | Meaning |
|---|---:|---|
| `$schema` | yes | Must be `https://atlasarc.io/schemas/evaluator-config-v1.schema.json`. Editors can use it for validation and completion. |
| `configVersion` | yes | Must be `1`. Configuration versions are independent of artifact versions. |
| `repositoryRoot` | no | Start path for locating the nearest owning Git root, relative to the configuration file. Defaults to `.`. |
| `sources` | yes | One or more uniquely named evidence sources. |

After the Git root is found, paths inside a source are resolved relative to that root—not relative
to the configuration file.

## Common source fields

| Field | Required | Meaning |
|---|---:|---|
| `id` | yes | A unique, stable label used in results and diagnostics, such as `jvm:whole-project` or `typescript:frontend`. It describes evidence; it does not create a separate governance namespace. |
| `backend` | yes | `jvm-bytecode` or `typescript-artifact`. |
| `root` | TypeScript only in practice | Source-tree root used to resolve dependency-cruiser module paths. Defaults to `.`. |

Several sources can be evaluated together. IDs must be unique, and all acquired sources must agree
on path case sensitivity. Any invalid source makes the complete evaluation invalid rather than
allowing the other sources to produce a falsely clean result.

## JVM bytecode evidence

A JVM source accepts two lists:

| Field | Required | Meaning |
|---|---:|---|
| `classDirectories` | yes | One or more directories containing compiled `.class` files. JAR dependencies are not imported. |
| `sourceRoots` | no | Java/Kotlin roots used for stable source identities and freshness checks. Strongly recommended for governance parity. |

Configured directories must exist, and at least one importable class must be present. If a current
`.java` or `.kt` file is newer than the newest configured `.class` file, evaluation fails closed
until the project is rebuilt.

### Genuinely module-less code

Omit `module` from every class and source root when all configured classes belong to one
module-less JVM universe:

```json
{
  "id": "jvm:main",
  "backend": "jvm-bytecode",
  "classDirectories": [{"path": "target/classes"}],
  "sourceRoots": [{"path": "src/main/java"}]
}
```

Module-less does not mean “module name unknown.” Use it only when a module boundary is not part of
the repository's architecture identity.

### Named modules and split packages

For a multi-module repository, assign the same stable module name to each module's class and source
root:

```json
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
```

The evaluator rejects a mixture of named and unlabelled roots. Equal package names in different
modules remain different architecture units, so a governance record for `billing:com.example.shared`
cannot govern `orders:com.example.shared` by accident.

Use names that remain stable between the IDE, local builds, and CI. A Maven or Gradle module name is
usually the right choice; an absolute checkout path is not.

## TypeScript artifact evidence

AtlasArc.io CI consumes dependency-cruiser JSON rather than running Node tooling itself:

```shell
npx depcruise --output-type json src > .atlasarc/depgraph.json
```

Configure the source tree and artifact:

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

`dependencyCruiserJson` is required, and JVM `classDirectories` are not allowed. The artifact must
contain a top-level `modules` array with in-repository source modules. Dependencies outside the
repository and `node_modules` are excluded.

If a represented source file is newer than the JSON artifact, evaluation is invalid until the
artifact is regenerated.

## Configuration file or direct options

A committed file is the recommended team contract. For experiments or simple build scripts, the
same source can be described directly:

```shell
java -jar atlasarc-ci-<version>-standalone.jar evaluate \
  --backend jvm-bytecode \
  --source-id jvm:whole-project \
  --classes target/classes \
  --source-root src/main/java \
  --repository-root .
```

For named modules, use `[module=]<path>`:

```shell
--classes billing=billing/target/classes \
--source-root billing=billing/src/main/java
```

For TypeScript:

```shell
java -jar atlasarc-ci-<version>-standalone.jar evaluate \
  --backend typescript-artifact \
  --source-id typescript:frontend \
  --root . \
  --dependency-cruiser .atlasarc/depgraph.json
```

`--config` cannot be combined with direct source options. Both modes accept `--format` and
`--output`.

## Establish an existing-debt baseline

Baseline generation requires a configuration file because AtlasArc must prove that the evidence is
complete before writing durable governance. Preview the exact records without changing the file:

```shell
java -jar atlasarc-ci-<version>-standalone.jar baseline \
  --config .atlasarc/evaluator.json
```

The preview distinguishes all current problem references from the narrower cycle-breaking edges
and exact Debt records selected to make the problem graph acyclic. Add `--write` only after
reviewing that proposal. Optional `--reason` and `--ticket` values apply to the newly generated Debt
records. Baseline output supports human text and JSON, not SARIF. See
[Establish a cycle-debt baseline](cycle-debt-baseline.md) for the safety conditions, record
semantics, and adoption workflow.

## Output contracts

| Option | Purpose |
|---|---|
| `--format human` | Concise terminal output for developers. This is the default. |
| `--format json` | Versioned machine result containing sources, record statuses, problem groups, edges, issues, and summary counts. |
| `--format sarif` | SARIF output for code-scanning ingestion. |
| `--output <path>` | Write the selected representation to a file instead of standard output. Parent directories are created. |

Machine output deliberately omits governance reasons, tickets, and absolute workstation paths.

| Exit | Meaning |
|---:|---|
| `0` | Valid evaluation with no unaccepted cycle. |
| `1` | Valid evaluation with one or more unaccepted cycles. |
| `2` | Invalid configuration, governance, acquisition, freshness, or output. |
| `3` | Unexpected internal evaluator error. |

Treat exit `2` as a broken gate, not as “no cycles.” AtlasArc.io CI fails closed whenever it cannot
prove the evidence and governance are usable.

## Keep the configuration visible to Git

If the repository broadly ignores `.atlasarc`, add exceptions for durable configuration:

```gitignore
!/.atlasarc/
/.atlasarc/*
!/.atlasarc/sources.json
!/.atlasarc/evaluator.json
!/.atlasarc/governance/
!/.atlasarc/governance/**
```

Generated artifacts such as `.atlasarc/depgraph.json` should remain ignored and be regenerated by
the build that runs the evaluator.

## Troubleshooting

| Symptom | Check |
|---|---|
| No owning Git repository | `repositoryRoot` must resolve to a path inside the intended Git worktree. |
| Configured JVM directory is missing | Run the compiler first and verify every path is relative to the Git root. |
| No importable classes | Confirm the class directories contain project `.class` files, not only resources or dependency JARs. |
| Mixed named and module-less roots | Add a stable module to every JVM root or remove every module label. |
| Evidence is stale | Recompile JVM sources or regenerate dependency-cruiser JSON immediately before evaluation. |
| TypeScript graph contains no repository modules | Verify dependency-cruiser's `baseDir`, the configured `root`, and the artifact's module paths. |
| A record becomes ambiguous | Qualify split JVM packages with stable module names; see [Govern cycle decisions](governance-decisions.md#module-qualified-identities). |

## Authoritative resources

- [Evaluator configuration schema](../atlasarc-ci/src/main/resources/evaluator-config.schema.json)
- [JVM example](../atlasarc-ci/examples/jvm-evaluator.json)
- [TypeScript example](../atlasarc-ci/examples/typescript-evaluator.json)
- [Governance decisions guide](governance-decisions.md)
