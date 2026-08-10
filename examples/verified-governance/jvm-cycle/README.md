# Java cycle: JUnit, standalone, and ArchUnit

`demo.orders` and `demo.billing` deliberately depend on each other. The same Maven project supports
all three AtlasArc.io CI enforcement paths.

Start in this directory, create a Git repository if this is a standalone copy, and compile current
bytecode:

```shell
git init
mvn compile
mkdir -p .atlasarc/governance
cp scenarios/ungoverned-cycles.json .atlasarc/governance/cycles.json
```

Download `atlasarc-ci-1.4.1-standalone.jar` from the
[1.4.1 release](https://github.com/seamraworks/atlasarc-ci/releases/tag/1.4.1), then prove the
standalone gate rejects the cycle with exit `1`:

```shell
java -jar <path-to>/atlasarc-ci-1.4.1-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

The ordinary JUnit test and the native ArchUnit rule express the same gate in the normal test
lifecycle and fail for the same ungoverned cycle. Run them independently with:

```shell
mvn -Dtest=demo.architecture.ConfiguredEvaluatorTest test
mvn -Dtest=demo.architecture.CycleGovernanceTest test
```

Now install the reviewed Debt decision and repeat both commands:

```shell
cp scenarios/governed-cycles.json .atlasarc/governance/cycles.json
mvn -Dtest=demo.architecture.ConfiguredEvaluatorTest test
mvn -Dtest=demo.architecture.CycleGovernanceTest test
java -jar <path-to>/atlasarc-ci-1.4.1-standalone.jar evaluate \
  --config .atlasarc/evaluator.json --format human
```

All three integrations now pass. The dependency remains in the structural code; the decision removes
the accepted `demo.orders` → `demo.billing` references only from the cycle problem graph.
