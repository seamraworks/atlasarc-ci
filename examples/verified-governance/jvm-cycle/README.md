# Java cycle: standalone and ArchUnit

`demo.orders` and `demo.billing` deliberately depend on each other. The same Maven project supports
both AtlasArc.io CI enforcement paths.

Start in this directory, create a Git repository if this is a standalone copy, and compile current
bytecode:

```shell
git init
mvn compile
mkdir -p .atlasarc/governance
cp scenarios/ungoverned-cycles.json .atlasarc/governance/cycles.json
```

Download `atlasarc-ci-1.2.0-standalone.jar` from the
[1.2.0 release](https://github.com/seamraworks/atlasarc-ci/releases/tag/1.2.0), then prove the
standalone gate rejects the cycle with exit `1`:

```shell
java -jar <path-to>/atlasarc-ci-1.2.0-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

The ArchUnit test expresses the same gate in the normal test lifecycle and fails for the same
ungoverned cycle:

```shell
mvn test
```

Now install the reviewed Debt decision and repeat both commands:

```shell
cp scenarios/governed-cycles.json .atlasarc/governance/cycles.json
mvn test
java -jar <path-to>/atlasarc-ci-1.2.0-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

Both integrations now pass. The dependency remains in the structural code; the decision removes
the accepted `demo.orders` → `demo.billing` references only from the cycle problem graph.
