# Verified cycle-governance examples

[![Verified examples](https://github.com/seamraworks/atlasarc-ci/actions/workflows/verified-examples.yml/badge.svg)](https://github.com/seamraworks/atlasarc-ci/actions/workflows/verified-examples.yml)

These two deliberately small projects prove the public AtlasArc.io CI integrations against the
same before-and-after story:

1. two architecture units depend on each other;
2. empty repository governance leaves the cycle ungoverned and the gate rejects it; and
3. one reviewed Debt decision breaks the problem cycle and the gate passes without deleting the
   structural dependency.

| Project | JUnit adapter | Standalone evaluator | ArchUnit adapter |
|---|---:|---:|---:|
| [Java](jvm-cycle/README.md) | rejection + clean | rejection + clean | rejection + clean |
| [TypeScript](typescript-cycle/README.md) | covered by configured evaluator | rejection + clean | not applicable |

The eight GitHub jobs are expected-outcome assertions. A rejection job is green only when AtlasArc
returns its documented problem verdict and identifies the intended cycle. A compiler error, stale
evidence, invalid configuration, or unrelated test failure makes the job red.

Run one cell from the repository root:

```shell
bash examples/verified-governance/verify-example.sh jvm junit ungoverned
bash examples/verified-governance/verify-example.sh jvm junit governed
bash examples/verified-governance/verify-example.sh jvm standalone ungoverned
bash examples/verified-governance/verify-example.sh jvm standalone governed
bash examples/verified-governance/verify-example.sh jvm archunit ungoverned
bash examples/verified-governance/verify-example.sh jvm archunit governed
bash examples/verified-governance/verify-example.sh typescript standalone ungoverned
bash examples/verified-governance/verify-example.sh typescript standalone governed
```

The runner copies the selected project into a temporary Git repository, installs the chosen
`cycles.json`, produces fresh evidence, and consumes the released artifacts from Maven Central. It
does not modify the checked-in example or upload artifacts. The project-specific READMEs show the
underlying commands without the verification harness so you can copy and adapt them directly.

The AtlasArc.io CI version is declared once as `atlasarc-ci.version` in
[`jvm-cycle/pom.xml`](jvm-cycle/pom.xml). Both projects and all eight verification cells use it.
