# Releasing AtlasArc.io CI

This repository releases the public AtlasArc.io CI artifacts under `io.atlasarc`. It does **not**
release AtlasArc.io for IntelliJ. The private `atlasarc.io-plugin` repository has a separate
Marketplace version and release workflow; never change that version as part of an AtlasArc.io CI
release.

All four Maven artifacts share one version and are released together:

- `atlasarc-governance-core`
- `atlasarc-ci`
- `atlasarc-junit`
- `atlasarc-archunit`

## 1. Choose exactly one versioning mode

CircleCI exposes patch, minor, major, and as-is approval gates. Do not combine their responsibilities.

| Version state on `main` | Approval gate |
|---|---|
| The desired release version is already committed in every reactor POM | **as-is** |
| The current version has not been changed and the release job should calculate the next version | patch, minor, or major |

The key rule is: **if the version was changed manually, approve as-is**. Approving a semantic bump
after committing that bump asks the release automation to change the version again.

For an as-is release, update the root `pom.xml` plus the parent version in each module POM:

- `atlasarc-governance-core/pom.xml`
- `atlasarc-archunit/pom.xml`
- `atlasarc-ci/pom.xml`
- `atlasarc-junit/pom.xml`

Confirm that no old reactor version remains, run the release-shaped build, and commit only the POMs:

```shell
mvn --batch-mode verify -Ppublish-cli
git add -- \
  pom.xml \
  atlasarc-governance-core/pom.xml \
  atlasarc-archunit/pom.xml \
  atlasarc-ci/pom.xml \
  atlasarc-junit/pom.xml
git commit -m "release(ci): prepare <version>"
git push origin main
```

## 2. Run and approve the CircleCI pipeline

The `build-test-and-release` workflow must first pass `build-and-test`. Then approve only the gate
selected above. Leave the other approval jobs on hold.

The locally configured CircleCI CLI already provides authenticated project access; do not request a
new Maven Central signing secret or deployment token. The current CLI can inspect and run pipelines
but does not expose a manual-approval command. Approve the gate in CircleCI's UI, or use the
CircleCI v2 workflow-approval API with the existing CLI credential. Never print that credential or
an artifact URL containing a temporary access token.

If the GitHub push does not create a pipeline, trigger the repository's existing pipeline definition
for `main` with `circleci pipeline run` (the organization, project, and pipeline-definition IDs are
shown in CircleCI Project Settings). Do not create a second pipeline definition.

The selected deploy job publishes Maven Central artifacts and pushes the release tag. It does not
create the GitHub Release page.

## 3. Verify publication before changing release claims

Wait for the selected deploy job to succeed, then fetch tags and verify that `<version>` resolves to
the intended release commit:

```shell
git fetch origin --tags
git show-ref --tags | grep "refs/tags/<version>$"
```

Verify all four Maven Central directories. Each module must contain its main JAR, sources JAR,
Javadoc JAR, and POM. `atlasarc-ci` must additionally contain the standalone JAR and ZIP:

```text
https://repo.maven.apache.org/maven2/io/atlasarc/atlasarc-governance-core/<version>/
https://repo.maven.apache.org/maven2/io/atlasarc/atlasarc-ci/<version>/
https://repo.maven.apache.org/maven2/io/atlasarc/atlasarc-junit/<version>/
https://repo.maven.apache.org/maven2/io/atlasarc/atlasarc-archunit/<version>/
```

Do not describe a coordinate as released merely because the CircleCI deploy job started or the Git
tag exists. Maven Central visibility is the publication boundary.

## 4. Create the GitHub Release

Match the previous release shape: title `AtlasArc.io CI <version>`, concise release notes, the
standalone JAR, the standalone ZIP, and `SHA256SUMS`. Prefer downloading the just-published
standalone assets from Maven Central before attaching them so the GitHub assets are byte-for-byte
identical to the Maven release.

Create the release against the existing verified tag:

```shell
gh release create <version> \
  atlasarc-ci-<version>-standalone.jar \
  atlasarc-ci-<version>-standalone.zip \
  SHA256SUMS \
  --verify-tag \
  --title "AtlasArc.io CI <version>" \
  --notes "<release notes>"
```

Verify the published release and asset digests with `gh release view <version> --json assets,url`.

## 5. Complete post-release proof and documentation

After Maven Central is visible:

1. update README and technical-guide coordinates without preview or interim caveat language;
2. update verified examples to consume the released coordinate;
3. run all governed-pass and ungoverned-rejection example cells;
4. push those documentation/example commits and confirm the clean GitHub Actions matrix passes; and
5. only then update the AtlasArc website, roadmap, feature inventory, and private content plan to
   call the item released.

Keep these post-release changes separate from the tagged release-preparation commit. This preserves
a reviewable tag while making publication proof and documentation updates independently auditable.
