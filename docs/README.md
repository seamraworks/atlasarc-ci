# AtlasArc.io CI documentation

Start with the repository's [main README](../README.md) if AtlasArc.io CI is new to you. It explains
the problem, the evidence-and-policy workflow, the two enforcement paths, and a first runnable
example.

Use these guides when you are ready to configure or operate the gate:

| Guide | Use it when |
|---|---|
| [Configure the evaluator](evaluator-configuration.md) | You need to describe JVM or TypeScript evidence, module ownership, paths, freshness, output, or direct CLI invocation. |
| [Govern cycle decisions](governance-decisions.md) | You need to create, review, understand, repair, or remove records in `.atlasarc/governance/cycles.json`. |
| [Establish a cycle-debt baseline](cycle-debt-baseline.md) | You are adopting the gate in a repository with existing cycles and want to record the current backlog as exact Debt before new cycles start failing. |
| [Run the verified examples](../examples/verified-governance/README.md) | You want copyable Java and TypeScript projects that prove governed-pass and ungoverned-reject behavior in GitHub Actions and locally. |

## Machine contracts

The guides explain intent and workflow. These schemas define the exact accepted JSON:

- [`evaluator-config.schema.json`](../atlasarc-ci/src/main/resources/evaluator-config.schema.json)
- [`cycle-governance-v1.schema.json`](../atlasarc-governance-core/src/main/resources/io/atlasarc/governance/cycle-governance-v1.schema.json)

Versioned examples live under [`atlasarc-ci/examples`](../atlasarc-ci/examples), and the complete
multi-module regression fixture lives under
[`test-fixtures/cycle-governance/multi-module-split-packages`](../test-fixtures/cycle-governance/multi-module-split-packages).

## Extension API

Tools that provide another evidence source or governance workflow can depend on
`io.atlasarc:atlasarc-governance-core`. The public entry points are
`GovernanceEvidenceSnapshot`, `GovernanceEvaluationInput`, and
`CycleGovernanceEvaluator.evaluate`. Baseline-capable tools can use `CycleDebtBaselinePlanner` to
produce a pure, validated proposal before performing their own explicit revision-checked write. The
core owns validation, matching, reference coverage, cycle calculation, and proposal semantics;
adapters own evidence acquisition, consent, presentation, and filesystem orchestration.
