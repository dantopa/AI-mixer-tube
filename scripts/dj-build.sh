#!/usr/bin/env bash
# Builds the app with the AI DJ: applies the core patch series, then runs Gradle.
#
#   scripts/dj-build.sh                                 -> :androidApp:assembleDebug variant task (see below)
#   scripts/dj-build.sh :djAndroid:testDebugUnitTest    -> any Gradle tasks/args
#
# ANDROID_HOME defaults to /opt/android-sdk and local.properties gets `sdk.dir` when missing (it is gitignored).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -e core/.git ]]; then
  git submodule update --init --depth 1 core
fi

"$ROOT/scripts/apply-core-patches.sh"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
if [[ ! -f local.properties ]] || ! grep -q '^sdk.dir=' local.properties; then
  echo "sdk.dir=$ANDROID_HOME" >> local.properties
fi

if [[ $# -eq 0 ]]; then
  set -- :androidApp:assembleFullDebug
fi

exec ./gradlew "$@" -Dorg.gradle.workers.max="${GRADLE_WORKERS:-2}" --console=plain
