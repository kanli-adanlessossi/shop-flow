#!/usr/bin/env bash
# Fails when the Surefire reports show more skipped tests than the sources declare with @Disabled.
# The Testcontainers tests use @Testcontainers(disabledWithoutDocker = true): without Docker they are
# skipped, the build stays green and the integration coverage is gone. A skip nobody wrote down is a failure.
# Run from the repository root after the build.
set -euo pipefail

reports=$(find . -path '*/target/surefire-reports/TEST-*.xml' -type f)
if [[ -z "$reports" ]]; then
  echo "::error title=No test reports::No Surefire reports found; did the build run the tests?"
  exit 1
fi

# The root element of each report carries skipped="N" (a class skipped as a whole counts all its tests).
skipped=0
while IFS= read -r report; do
  n=$(grep -m1 -o ' skipped="[0-9]*"' "$report" | grep -o '[0-9]*' || true)
  skipped=$(( skipped + ${n:-0} ))
done <<< "$reports"

# Skips that are written down: one per @Disabled annotation in the test sources.
allowed=$(grep -rE --include='*.java' '^\s*@Disabled\b' . 2>/dev/null | grep -c '/src/test/' || true)

echo "Skipped tests: ${skipped} (allowed by @Disabled annotations: ${allowed})"
if (( skipped > allowed )); then
  echo "::error title=Unexpected skipped tests::${skipped} tests were skipped but only ${allowed} are marked @Disabled. Without Docker the Testcontainers tests skip silently."
  exit 1
fi
