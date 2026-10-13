#!/bin/sh
# Fetch EEMBC CoreMark at a pinned commit (not vendored; see its LICENSE.md).
set -e
DIR=${1:-third_party/coremark}
REV=1f483d5b8316753a742cbf5590caf5bd0a4e4777
[ -d "$DIR/.git" ] || git clone --quiet https://github.com/eembc/coremark "$DIR"
git -C "$DIR" checkout --quiet "$REV"
echo "coremark $REV in $DIR"
