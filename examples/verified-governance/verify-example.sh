#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <jvm|typescript> <standalone|archunit> <ungoverned|governed>" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage
language="$1"
integration="$2"
scenario="$3"

case "$language:$integration" in
  jvm:standalone|jvm:archunit|typescript:standalone) ;;
  *) usage ;;
esac
case "$scenario" in
  ungoverned|governed) ;;
  *) usage ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
version_pom="$script_dir/jvm-cycle/pom.xml"
atlasarc_version="$(mvn --quiet --file "$version_pom" help:evaluate -Dexpression=atlasarc-ci.version -DforceStdout)"
if [[ -z "$atlasarc_version" || "$atlasarc_version" == *'['* ]]; then
  echo "Could not read atlasarc-ci.version from $version_pom" >&2
  exit 2
fi

project_name="$language-cycle"
project_source="$script_dir/$project_name"
work_root="$(mktemp -d "${TMPDIR:-/tmp}/atlasarc-verified-example.XXXXXX")"
trap 'rm -rf "$work_root"' EXIT
cp -R "$project_source/." "$work_root/"
git -C "$work_root" init --quiet
mkdir -p "$work_root/.atlasarc/governance"
cp "$work_root/scenarios/$scenario-cycles.json" "$work_root/.atlasarc/governance/cycles.json"

expected_exit=0
expected_verdict="clean"
expected_description="clean governance result"
if [[ "$scenario" == "ungoverned" ]]; then
  expected_exit=1
  expected_verdict="problems"
  expected_description="ungoverned-cycle rejection"
fi

download_dir="$repository_root/target/verified-examples"
standalone_jar="$download_dir/atlasarc-ci-$atlasarc_version-standalone.jar"
download_standalone() {
  if [[ ! -f "$standalone_jar" ]]; then
    mkdir -p "$download_dir"
    curl --fail --location --retry 3 \
      --output "$standalone_jar" \
      "https://repo.maven.apache.org/maven2/io/atlasarc/atlasarc-ci/$atlasarc_version/atlasarc-ci-$atlasarc_version-standalone.jar"
  fi
}

if [[ "$language" == "jvm" ]]; then
  mvn --quiet --file "$work_root/pom.xml" -DskipTests compile
else
  (
    cd "$work_root"
    npm ci --no-audit --no-fund
    npm run compile
    npm run evidence
  )
fi

actual_exit=0
proof_file=""
if [[ "$integration" == "standalone" ]]; then
  download_standalone
  mkdir -p "$work_root/target"
  proof_file="$work_root/target/atlasarc-result.json"
  set +e
  java -jar "$standalone_jar" evaluate \
    --config "$work_root/.atlasarc/evaluator.json" \
    --format json \
    --output "$proof_file"
  actual_exit=$?
  set -e

  if [[ ! -f "$proof_file" ]] || ! grep -Eq "\"verdict\"[[:space:]]*:[[:space:]]*\"$expected_verdict\"" "$proof_file"; then
    echo "Standalone output did not contain verdict $expected_verdict:" >&2
    [[ -f "$proof_file" ]] && cat "$proof_file" >&2
    exit 1
  fi
  if [[ "$scenario" == "ungoverned" ]] && ! grep -Eq 'demo\.orders|src/orders' "$proof_file"; then
    echo "Standalone output did not identify the intended example cycle:" >&2
    cat "$proof_file" >&2
    exit 1
  fi
else
  proof_file="$work_root/target/archunit.log"
  mkdir -p "$work_root/target"
  set +e
  mvn --quiet --file "$work_root/pom.xml" -Dtest=demo.architecture.CycleGovernanceTest test \
    >"$proof_file" 2>&1
  actual_exit=$?
  set -e

  if [[ "$scenario" == "ungoverned" ]] && ! grep -q 'AtlasArc.io found an ungoverned cycle' "$proof_file"; then
    echo "ArchUnit did not report the intended AtlasArc.io cycle violation:" >&2
    cat "$proof_file" >&2
    exit 1
  fi
fi

if [[ "$actual_exit" -ne "$expected_exit" ]]; then
  echo "Expected exit $expected_exit for $language/$integration/$scenario, got $actual_exit:" >&2
  [[ -f "$proof_file" ]] && cat "$proof_file" >&2
  exit 1
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Verified AtlasArc.io CI example"
    echo
    echo "| Field | Value |"
    echo "|---|---|"
    echo "| Evidence | $language |"
    echo "| Integration | $integration |"
    echo "| Governance scenario | $scenario |"
    echo "| Expected command exit | $expected_exit |"
    echo "| Observed command exit | $actual_exit |"
    echo "| Proof | $expected_description |"
  } >>"$GITHUB_STEP_SUMMARY"
fi

echo "Verified $language / $integration / $scenario: $expected_description (exit $actual_exit)."
