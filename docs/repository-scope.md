# Define repository analysis scope

Repository scope answers one question before AtlasArc evaluates architecture: **which architecture
units are outside this repository's governed evidence universe?** The policy is committed at the
Git root as `.atlasarc/governance/scope.json` and is applied identically by the standalone
evaluator, the ArchUnit/JUnit adapter, AtlasArc for IntelliJ, and whole-model reports.

Use scope for durable boundaries such as generated code, vendored code, framework output, or a
deliberately fenced migration area. Do not use it merely to make a cycle disappear: an in-scope
cycle that the team accepts belongs in `cycles.json` as Intentional or Debt.

## Evaluation order

AtlasArc loads scope before it classifies cycles or matches cycle decisions:

```text
acquired dependency evidence
  -> .atlasarc/governance/scope.json
    -> in-scope architecture units and dependencies
      -> cycle detection
      -> .atlasarc/governance/cycles.json matching
      -> verdict and report output
```

When a rule matches an architecture unit, AtlasArc removes that unit and every dependency entering
or leaving it. A missing `scope.json` is a valid empty policy. An invalid, unreadable, or Git-ignored
file fails CI closed; AtlasArc never falls back to silently evaluating a different scope that the
rest of the team cannot reproduce.

## Minimal policy

```json
{
  "$schema": "https://atlasarc.io/schemas/repository-scope-v1.schema.json",
  "schemaVersion": 1,
  "exclusions": {
    "generated-adapters": {
      "selector": {
        "kind": "jvm-package-pattern",
        "module": "billing",
        "pattern": "com.acme.billing.generated.**"
      },
      "reason": "Adapters in this package are generated from the billing API contract."
    }
  }
}
```

Every exclusion has a stable rule ID, one semantic selector, and a required human reason. Rule IDs
use lowercase letters, digits, dots, underscores, or hyphens.

## Selector language

Patterns operate on architecture-unit segments, not arbitrary substrings:

- a literal segment matches itself;
- `*` matches exactly one segment; and
- `**` matches zero or more segments.

Wildcards must occupy a complete segment. Matching follows the case policy of the acquired
evidence.

### JVM packages

Use `kind: "jvm-package-pattern"` and `.`-separated package names. Module ownership is deliberate:

| `module` value | Meaning |
|---|---|
| a stable name such as `billing` | Match only that module's package instance. |
| `"*"` | Match the package in every named module and in legitimately module-less evidence. |
| omitted | Match only legitimately module-less JVM evidence. |

This prevents an exclusion for `billing:com.acme.shared` from hiding the equal package name in the
`orders` module. Unattributed JVM evidence cannot be excluded; AtlasArc retains it so missing module
ownership cannot manufacture a clean verdict.

### TypeScript source folders

Use `kind: "typescript-source-folder-pattern"` with normalized repository-relative `/`-separated
source-folder paths:

```json
"generated-client": {
  "selector": {
    "kind": "typescript-source-folder-pattern",
    "pattern": "packages/*/src/generated/**"
  },
  "reason": "Generated API clients are governed by their source specifications."
}
```

TypeScript selectors do not have a JVM-style module field.

## Diagnostics and audit output

Evaluation output records the scope-file revision, rule count, matched-rule count, stale-rule
count, and retained/excluded architecture-unit and dependency totals. Human, JSON, and SARIF output
therefore disclose the policy that shaped the verdict without exposing rule reasons in machine
logs.

A valid rule that matches no current architecture unit is reported as `stale-scope-rule`. This is a
warning rather than a clean-up side effect: the rule remains committed until a reviewer confirms it
was renamed or is no longer needed.

Cycle decisions whose source or target is removed by repository scope are reported as not in the
current analysis. They neither suppress in-scope evidence nor make the evaluation invalid.

## Scope is not a view

Repository scope changes the evidence evaluated in CI. Interactive filters and shared
investigation views in AtlasArc for IntelliJ only change how an in-scope model is explored; the
standalone evaluator and ArchUnit adapter never read `.atlasarc/views.json`. Choosing a view cannot
weaken the build gate.

Review scope changes like build configuration. In particular, a broad wildcard can also exclude
future packages, so its reason and measured impact should make that intent obvious in code review.
