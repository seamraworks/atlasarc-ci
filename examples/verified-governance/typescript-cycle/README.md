# TypeScript cycle: standalone evaluator

`src/orders` and `src/billing` deliberately import each other. This fixture verifies the standalone
process boundary. The same configured evaluator can also consume this dependency-cruiser artifact
through `atlasarc-junit`; the native ArchUnit adapter remains JVM-specific.

Start in this directory, create a Git repository if this is a standalone copy, install the pinned
toolchain, and generate current dependency evidence:

```shell
git init
npm ci
npm run compile
npm run evidence
mkdir -p .atlasarc/governance
cp scenarios/ungoverned-cycles.json .atlasarc/governance/cycles.json
```

Download `atlasarc-ci-1.4.0-standalone.jar` from the
[1.4.0 release](https://github.com/seamraworks/atlasarc-ci/releases/tag/1.4.0), then prove the gate
rejects the ungoverned cycle with exit `1`:

```shell
java -jar <path-to>/atlasarc-ci-1.4.0-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

Install the reviewed Debt decision and repeat evaluation:

```shell
cp scenarios/governed-cycles.json .atlasarc/governance/cycles.json
java -jar <path-to>/atlasarc-ci-1.4.0-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

The evaluator now exits `0`. The imports remain visible in the structural evidence; governance
removes the accepted `src/orders` → `src/billing` references only from the cycle problem graph.
