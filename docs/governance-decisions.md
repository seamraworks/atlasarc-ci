# Govern cycle decisions with `cycles.json`

`.atlasarc/governance/cycles.json` is the repository's durable record of accepted cycle-forming
dependencies. It lets a team distinguish three outcomes:

- **fix it now** — remove the dependency or otherwise break the cycle;
- **intentional** — keep the dependency as a deliberate architecture choice; or
- **debt** — allow it for now while preserving an explicit cleanup obligation.

The file belongs in version control. A governance change should be reviewed with the code and
reasoning that justify it, and the same committed file is consumed by AtlasArc.io for IntelliJ, the
standalone evaluator, and the ArchUnit/JUnit adapter.

## The decision workflow

1. Build or analyze current repository evidence.
2. Inspect an unaccepted cycle and the concrete references that form it.
3. Refactor the dependency, or choose a governance scope that is no broader than the decision.
4. Classify an accepted dependency as `INTENTIONAL` or `DEBT` and record a reason.
5. Commit `cycles.json` with the related code review.
6. Let local tests and CI re-evaluate the decision against fresh evidence.
7. Repair or remove records when packages, modules, declarations, or concrete references move.

AtlasArc.io for IntelliJ supplies the intended visual authoring, baseline, reclassification, repair,
and removal workflow. The contract is public and may be maintained by other tooling. Ordinary CI
evaluation is always read-only; the separate, explicitly invoked
[`baseline --write`](cycle-debt-baseline.md) adoption command is the only standalone mutation flow.

## Start with no accepted decisions

An empty document is valid:

```json
{
  "$schema": "https://atlasarc.io/schemas/cycle-governance-v1.schema.json",
  "schemaVersion": 1,
  "records": {}
}
```

With fresh evidence, every detected cycle is then unaccepted and produces a problem verdict.

## Anatomy of a record

Records are stored in an object keyed by a stable record ID:

```json
{
  "$schema": "https://atlasarc.io/schemas/cycle-governance-v1.schema.json",
  "schemaVersion": 1,
  "records": {
    "orders-to-billing-debt": {
      "analysisSource": {
        "id": "jvm:whole-project",
        "backend": "jvm-bytecode",
        "language": "java"
      },
      "scope": "package",
      "ownerSide": "source",
      "source": {
        "architectureUnit": "com.example.orders",
        "module": "orders"
      },
      "target": {
        "architectureUnit": "com.example.billing",
        "module": "billing"
      },
      "referenceIds": [],
      "kind": "DEBT",
      "reason": "Legacy ordering flow still calls billing directly.",
      "ticket": "ARCH-42"
    }
  }
}
```

Record IDs must be 3–64 characters, start with an alphanumeric character, and then use only letters,
digits, `.`, `_`, or `-`. The ID is for durable review and reporting; matching is based on the
record's selector fields.

| Field | Meaning |
|---|---|
| `analysisSource` | Backend and owner language captured when the decision was made. Its `id` preserves evidence provenance but does not partition repository governance into separate stores. |
| `scope` | The architecture level owned by the decision. Supported scopes depend on the backend. |
| `ownerSide` | Whether the source or target endpoint owns the scope-specific decision. Reference scope always uses `source`. |
| `source`, `target` | Semantic identities for both ends of the dependency. |
| `dependencyKind` | Optional narrowing to a specific kind of dependency. |
| `referenceIds` | Concrete stable references. Required and non-empty only for `reference` scope; otherwise it must be empty. |
| `kind` | `INTENTIONAL` or `DEBT`. |
| `reason` | Required human explanation, up to 4096 characters. |
| `ticket` | Optional issue or work-item reference. |
| `display` | Optional labels and repository-relative paths for presentation. It does not change matching. |

Two records may not use the same complete selector. Change the existing record's classification or
reason instead of adding a duplicate.

## Choose the narrowest useful scope

| Backend | Scope | What it governs |
|---|---|---|
| JVM | `package` | Matching dependency references between the selected package identities. |
| JVM | `type` | Matching references owned by the selected source or target type. |
| JVM | `member` | Matching references owned by the selected method, constructor, or field identity. |
| JVM or TypeScript | `reference` | Only the listed concrete stable reference IDs. |
| TypeScript | `source-folder` | Matching imports between the selected source-folder architecture units. |
| TypeScript | `source-file` | Matching imports owned by the selected source or target file. |

A wider scope is durable when the architectural intent itself is wide—for example, one package is
allowed to depend on another. A reference scope is appropriate when only specific calls or imports
are accepted. Avoid choosing a package or folder merely to silence several unrelated references.

### Ownership in plain language

`ownerSide` identifies which endpoint carries the scope-specific language and declaration context:

- `source` means “accept dependencies from this source package, type, member, folder, or file to the
  recorded target”;
- `target` means “accept dependencies into this target package, type, member, folder, or file from
  the recorded source.”

For an individual `reference`, both endpoints and the stable reference ID already identify the
decision completely, so ownership is fixed to `source` and is not a meaningful user choice.

## Semantic identities

Every endpoint has an `architectureUnit`:

- a package FQN for Java/Kotlin, such as `com.example.orders`;
- a repository-relative source folder for TypeScript, such as `src/orders`.

Narrower scopes add only the identity needed for that scope:

| Field | Used for |
|---|---|
| `module` | Stable JVM module ownership and split-package disambiguation. |
| `type` | JVM type ownership. |
| `member.name` | JVM member ownership. |
| `member.descriptor` | JVM overload disambiguation when a name is not unique. |
| `sourceFile` | Repository-relative source identity, especially TypeScript source-file scope. |

Paths must be repository-relative, use forward slashes, and contain no `.` or `..` segments.

### Module-qualified identities

In a named-module JVM analysis, `(module, package FQN)` identifies a package. Equal package names in
different modules are different architecture units. Include `module` on both endpoints whenever
current evidence provides it.

If a module is omitted and the same identity exists in several modules, the record is `ambiguous`
and fails closed. The plugin can calculate viable source/target module pairings from current
dependency evidence and apply the selected repair.

Genuinely module-less code does not need a synthetic module. Omit `module` only when the code really
belongs to one module-less universe, not when attribution is missing or uncertain.

## Intentional architecture and debt

`INTENTIONAL` means the dependency is an accepted part of the intended architecture. `DEBT` means
the dependency is temporarily accepted while remaining an explicit cleanup obligation.

Both kinds can remove matching concrete references from cycle problem detection. They do not delete
or hide the dependency from the structural graph. If Intentional and Debt records overlap, Debt
wins so a broader intentional rule cannot erase a narrower debt signal.

Reasons should state the architectural decision, constraint, or repayment intent—not merely “ignore
this cycle.” A ticket is useful for Debt, but it does not replace the reason stored with the record.

## Why partial coverage still fails

A structural edge can represent several calls or imports. AtlasArc.io evaluates concrete references
before it calculates cycles:

1. active records contribute their matching reference IDs to accepted coverage;
2. an edge stays in the problem graph if **any** concrete reference remains uncovered; and
3. cycles are calculated over that remaining graph.

This “red wins” rule prevents a broad-looking decision or an outdated reference list from silently
hiding a new dependency on the same package-to-package edge.

## Record health

Every evaluation classifies each record against current evidence:

| Status | Meaning | Build effect |
|---|---|---|
| `active` | The selector matches current dependency evidence. | Matching references contribute accepted coverage. |
| `resolved` | The selected dependency or every recorded concrete reference is gone. | Does not invalidate the gate; consider removing the obsolete record. |
| `not-in-analysis` | A deliberately partial analysis does not cover this record. | The record is not applied and is not treated as proof about unexamined code. |
| `missing-source` | Covered evidence no longer contains the selected source identity. | Invalid; fails closed. |
| `missing-target` | Covered evidence no longer contains the selected target identity. | Invalid; fails closed. |
| `partial` | Only some concrete reference IDs still exist. | Invalid; fails closed. |
| `ambiguous` | A selector matches several concrete identities, commonly an unqualified split package. | Invalid; fails closed. |
| `unsupported` | Current evidence lacks the backend, language, scope, or freshness needed to evaluate the record. | Invalid; fails closed. |
| `invalid` | The record violates the schema or semantic validation rules. | Invalid; fails closed. |

The standalone evaluator returns exit `2` when a record has an invalid status. Repairing governance
is different from accepting a new cycle: retarget or qualify the original decision only when the
current evidence proves what it became.

## Dependency kinds and reference IDs

`dependencyKind` optionally narrows a selector. Supported values are:

- JVM: `method-call`, `constructor-call`, `field-access`, `structural`, or `unknown`;
- TypeScript: `runtime-import`, `type-only-import`, `dynamic-import`, `re-export`, or `unknown`.

Reference IDs are deterministic evidence identifiers generated from the analysis source, backend,
source and target language, semantic endpoints, and dependency kind. Do not invent them from line
numbers. Use current evaluator/plugin evidence when creating a `reference` record; line numbers and
local paths are not stable governance identities.

## Keep governance committed

If the repository ignores `.atlasarc`, restore the durable files explicitly:

```gitignore
!/.atlasarc/
/.atlasarc/*
!/.atlasarc/sources.json
!/.atlasarc/evaluator.json
!/.atlasarc/governance/
!/.atlasarc/governance/**
```

Generated evidence such as `depgraph.json` should remain ignored. Governance and evaluator
configuration should be reviewed and shared.

## Review checklist

Before merging a governance change, verify:

- the dependency really participates in the reported cycle;
- the chosen scope is no broader than the architectural decision;
- JVM modules are qualified wherever module ownership exists;
- Intentional versus Debt reflects the intended posture;
- the reason explains why the dependency is accepted;
- a Debt ticket points to actionable follow-up when available;
- no unrelated record became stale, partial, ambiguous, or invalid; and
- fresh local or CI evidence produces the expected verdict.

## Authoritative resources

- [Cycle-governance schema](../atlasarc-governance-core/src/main/resources/io/atlasarc/governance/cycle-governance-v1.schema.json)
- [Fully governed multi-module fixture](../test-fixtures/cycle-governance/multi-module-split-packages/governance/fully-governed.json)
- [Standalone evaluator guide](evaluator-configuration.md)
- [AtlasArc.io CI overview](../README.md)
