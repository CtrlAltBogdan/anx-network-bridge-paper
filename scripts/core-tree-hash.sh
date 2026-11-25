#!/usr/bin/env sh
set -eu
dir=${1:-core}
cd "$dir"
find src -type f | LC_ALL=C sort | while IFS= read -r f; do printf '%s  %s\n' "$(sha256sum "$f" | cut -c1-64)" "$f"; done | sha256sum | cut -c1-64
