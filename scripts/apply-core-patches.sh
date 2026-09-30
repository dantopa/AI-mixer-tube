#!/usr/bin/env bash
# Applies the fork's patch series (patches/core/*.patch) to the `core` git submodule checkout.
#
# Why patches: the `core` submodule (maxrave-dev/core) holds all player/data code and this fork cannot push to
# it, so every change to it is carried here as a series applied on top of the pinned submodule commit.
#
# Idempotent: a patch that is already in the working tree is skipped (detected with `git apply --reverse
# --check`), so the script can run before every build. It never commits inside the submodule; `git status`
# in `core/` shows the applied series as a dirty tree, which is expected.
#
#   scripts/apply-core-patches.sh           apply what is missing (fails loudly on a conflict)
#   scripts/apply-core-patches.sh --check   only report: applied / pending / CONFLICT per patch
#                                           (exit 0 = everything applied or cleanly applicable, 1 = a conflict)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
PATCH_DIR="$ROOT/patches/core"
MODE="apply"
[[ "${1:-}" == "--check" ]] && MODE="check"

if [[ ! -e "$CORE/.git" ]]; then
  echo "core submodule is not initialised; run: git submodule update --init --depth 1 core" >&2
  exit 2
fi

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
if [[ ${#patches[@]} -eq 0 ]]; then
  echo "no patches in $PATCH_DIR"
  exit 0
fi

status=0
# A series is stacked: a later patch may touch the lines an earlier one added, so the earlier patch no longer reverse-applies
# on its own once the later one is in. "Already applied" is therefore decided from the END of the series: the last patch
# that reverse-applies cleanly proves itself and every patch before it are in; only what comes after it is applied.
applied_upto=-1
for ((i = ${#patches[@]} - 1; i >= 0; i--)); do
  if git -C "$CORE" apply --reverse --check "${patches[$i]}" >/dev/null 2>&1; then
    applied_upto=$i
    break
  fi
done

for i in "${!patches[@]}"; do
  p="${patches[$i]}"
  name="$(basename "$p")"
  if ((i <= applied_upto)); then
    echo "applied   $name"
  elif git -C "$CORE" apply --check "$p" >/dev/null 2>&1; then
    if [[ "$MODE" == "apply" ]]; then
      git -C "$CORE" apply "$p"
      echo "APPLIED   $name"
    else
      echo "pending   $name"
    fi
  else
    echo "CONFLICT  $name  (does not apply to the current core checkout: was the submodule pin bumped?)" >&2
    status=1
  fi
done
exit $status
