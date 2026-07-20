# Contributing

Issues and focused pull requests are welcome. By submitting a contribution, you agree that it is
licensed under Apache License 2.0.

## Development

Use JDK 21 or newer. Run the complete local gate before opening a pull request:

```shell
./gradlew check releaseCandidate
```

Keep the module boundary intact:

- `atlasarc-governance-core` must remain independent of ArchUnit, IntelliJ, Node, and build tools;
- evidence acquisition belongs in an adapter;
- matching, red-wins coverage, SCC evaluation, and result semantics belong only in core;
- CLI evaluation remains read-only; governance mutation needs a separately reviewed command and
  atomic-write contract.

Changes to schemas, stable IDs, record matching, output formats, or exit codes need regression
tests and an explicit compatibility note. Do not put absolute paths, governance reasons, or ticket
contents into machine output fixtures.
