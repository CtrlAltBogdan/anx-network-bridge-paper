#!/usr/bin/env sh
set -eu

prop() { sed -n "s/^$1=//p" core.lock; }
repo=$(prop core.repository)
tag=$(prop core.tag)
commit=$(prop core.commit)
expected=$(prop core.treeSha256)
actual=$(scripts/core-tree-hash.sh core)

[ "$actual" = "$expected" ] || { echo "core.lock hash mismatch: $actual != $expected" >&2; exit 1; }

if [ "${1:-}" = "--remote" ]; then
  remote=$(git ls-remote --refs "$repo" "refs/tags/$tag" | awk 'NR == 1 {print $1}')
  [ -n "$remote" ] || { echo "Core tag $tag does not exist" >&2; exit 1; }
  [ "$remote" = "$commit" ] || { echo "Core tag $tag points to $remote, expected $commit" >&2; exit 1; }
fi

echo "Core lock verified: $tag $commit $actual"
