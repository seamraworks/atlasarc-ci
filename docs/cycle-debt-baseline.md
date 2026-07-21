# Establish a cycle-debt baseline

An established repository may already contain dependency cycles when AtlasArc.io CI is adopted.
The explicit `baseline` command selects cycle-breaking dependency edges and records only their
current concrete references as reviewable `DEBT` in `.atlasarc/governance/cycles.json`. Ordinary
evaluation can then pass for the known backlog while a genuinely new ungoverned cycle still fails.

This is an adoption step, not a second evaluation mode. The generated records use the ordinary
governance schema and remain visible to AtlasArc for IntelliJ, the standalone evaluator, and the
ArchUnit/JUnit adapter.

The command was introduced in AtlasArc.io CI 1.1.0. Narrow feedback-edge selection replaces the
original all-edges proposal in 1.2.0.

## Before you begin

Create a complete `.atlasarc/evaluator.json` for the repository and produce all configured
evidence. JVM classes must be newer than their Java/Kotlin sources; dependency-cruiser JSON must be
newer than the TypeScript files it represents. Multi-module JVM roots need stable module labels.

If the repository uses `.atlasarc/governance/scope.json`, review that policy first. Baseline
planning applies repository scope before selecting cycle-breaking edges and never creates Debt for
excluded architecture units.

The governance path must belong to a Git worktree, be writable, and be included by effective Git
ignore rules. AtlasArc refuses to create a baseline when it cannot prove these conditions.

## Preview first

Run the command without `--write`:

```shell
java -jar atlasarc-ci-<version>-standalone.jar baseline \
  --config .atlasarc/evaluator.json
```

The preview reports:

- current ungoverned problem-cycle groups and concrete references;
- the cycle-breaking unit edges selected from those groups;
- references in those groups that existing governance already covers;
- exact debt records that would be added;
- existing records that will remain untouched; and
- the verdict produced by the proposed document.

No file is changed. Use `--format json` for versioned machine-readable preview output. Baseline
output is human or JSON; SARIF remains an ordinary evaluation format.

## Write the baseline

After reviewing the preview, repeat it with explicit write intent:

```shell
java -jar atlasarc-ci-<version>-standalone.jar baseline \
  --config .atlasarc/evaluator.json \
  --write
```

Every generated record:

- has `scope: "reference"` and covers exactly one current concrete dependency;
- is classified as `DEBT`, never `INTENTIONAL`;
- retains source and target module identity when the evidence is module-qualified; and
- uses the standard reason `Established as existing cycle debt by the AtlasArc CI baseline.`

AtlasArc does not accept every edge in a strongly connected component. For components of up to 18
architecture units, it computes an exact feedback-edge set: minimize selected unit edges first,
then minimize the number of concrete references that must be governed. Stable source, target,
module, and analysis-source identities break remaining ties. Larger components use a bounded,
deterministic ordering heuristic and discard redundant cuts. In both paths AtlasArc applies the
proposal in memory and refuses it unless ordinary evaluation proves the resulting problem graph is
clean.

When one selected unit edge represents several calls or imports, every currently uncovered
reference on that edge receives its own exact record. Leaving even one uncovered would leave the
unit edge in the problem graph. References on non-selected SCC edges are not accepted.

Use `--reason "..."` to replace the standard reason and `--ticket "..."` to attach one shared
tracking reference to the generated records. Existing record IDs, selectors, classifications,
reasons, tickets, and display data are preserved exactly.

The write is deterministic, revision-checked, and atomic. If the governance file changes between
read and write, AtlasArc reports a conflict instead of overwriting it. Repeating the command against
unchanged evidence is a semantic and byte-for-byte no-op.

## Review and enable the gate

Review the resulting `cycles.json` like source code, then commit it. Run the ordinary read-only
evaluation in local automation and CI:

```shell
java -jar atlasarc-ci-<version>-standalone.jar evaluate \
  --config .atlasarc/evaluator.json
```

The baseline does not hide structural dependencies or store opaque cycle IDs. It removes only the
selected exact references from the cycle problem graph and recomputes strongly connected
components. A new dependency—including a new reference parallel to a selected edge—remains
uncovered and fails when it participates in a new ungoverned problem cycle. A new dependency that
does not form such a cycle is not a cycle-governance violation.

Use AtlasArc for IntelliJ's **Cycle Governance** dialog to establish the same baseline visually from
a fresh whole-project analysis. The governance hub distinguishes current problem cycles, selected
cycle-breaking edges, and exact debt records before writing, then supports normal review,
reclassification, repair, and removal.

## When AtlasArc refuses

No file is written when AtlasArc finds stale, missing, partial, unattributed, or ambiguous evidence;
invalid or blocking governance records; an unsupported exact-reference scope; an ignored or
read-only governance path; a missing Git root; a concurrent edit; or a result beyond the governance
record limit.

Fix the named condition and rerun the preview. AtlasArc does not fall back to broad package
acceptance because that could silently govern future dependencies.

## Related guides

- [Configure the evaluator](evaluator-configuration.md)
- [Govern cycle decisions](governance-decisions.md)
- [Repository README](../README.md)
