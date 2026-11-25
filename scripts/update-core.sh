#!/usr/bin/env sh
set -eu

tag=${1:?usage: scripts/update-core.sh vX.Y.Z}
repo=https://github.com/CtrlAltBogdan/anx-network-bridge-core.git
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

git -C "$tmp" init -q
git -C "$tmp" remote add origin "$repo"
git -C "$tmp" fetch -q --depth=1 origin "refs/tags/$tag"
commit=$(git -C "$tmp" rev-parse FETCH_HEAD)
mkdir "$tmp/export"
git -C "$tmp" archive FETCH_HEAD src | tar -x -C "$tmp/export"
hash=$(scripts/core-tree-hash.sh "$tmp/export")

rm -rf core/src
cp -R "$tmp/export/src" core/src
{
  echo "core.repository=$repo"
  echo "core.tag=$tag"
  echo "core.commit=$commit"
  echo "core.treeSha256=$hash"
} > core.lock

scripts/verify-core.sh --remote
echo "Review the core diff, run ./gradlew clean build, and commit core/ with core.lock."
