# Acquisition Parity Corpus

This module in the public `atlasarc-ci` repository is the authority for corpus version 1. The
private plugin mirrors the contract, fixtures, reviewed snapshots, README, and manifest
byte-for-byte; neither repository imports the other's implementation.

## Boundary

Each repository runs its real JVM and TypeScript acquirers over the same neutral inputs. The
canonical contract compares source freshness and capabilities, architecture units and JVM module
ownership, every exact reference tuple, erased member descriptors, member-governance eligibility,
dependency kinds, evaluation record statuses, problem groups, problem edges, and summary counts.

The corpus exercises Java overloads and constructors, field and bidirectional calls, Kotlin calls
and default-parameter bytecode, multiple tuples from one source expression, equal package names in
different JVM modules, TypeScript runtime/type-only/dynamic/re-export imports, mixed-backend
evaluation, JVM-only partial evaluation with neutral TypeScript governance, exact-reference
resolve/reactivate behavior, and stale or missing TypeScript artifacts.

## Documented normalization

Only these differences are normalized:

- collection order is canonicalized;
- repository-relative paths and line endings use the product's portable forms;
- `caseSensitive`, `evaluationComplete`, and `repositoryComplete` are excluded because the CLI and
  IDE orchestrators own those run-context declarations rather than the acquirers;
- diagnostic prose, producer/repository revisions, opaque node keys, and redundant standalone type
  inventory nodes are excluded.

Descriptors, eligibility flags, reference correlation, modules, dependency kinds, freshness,
record statuses, groups, and edges are never softened or discarded.

## Reviewed update workflow

1. Change inputs or the canonical contract in this public module first.
2. Regenerate snapshots explicitly:

   ```text
   mvn -pl atlasarc-ci -am test -Dtest=PublicAcquisitionParityCorpusTest -Dsurefire.failIfNoSpecifiedTests=false -Datlasarc.updateParityCorpus=true
   ```

3. Review the semantic JSON diff. A normal test run fails when a snapshot is absent or changed.
4. Increment `corpusVersion` for a contract/input change and refresh every manifest SHA-256 entry.
   Hashes use UTF-8 text with CRLF normalized to LF, so the lock is operating-system independent.
5. Mirror the authority files into the private plugin without editing them there.
6. Run the public test normally and the private `PluginAcquisitionParityCorpusTest`; commit the two
   repositories independently.

The older private ArchUnit golden suite has its own explicit
`-Datlasarc.updateAcquisitionGoldens=true` switch. Deleting a golden never records and passes
silently.
