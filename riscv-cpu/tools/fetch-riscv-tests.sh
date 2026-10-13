#!/bin/sh
# Fetch the official riscv-tests (and its env submodule) at pinned commits.
set -e
DIR=${1:-third_party/riscv-tests}
TESTS_REV=7b68f7b91f827f8d94cc1c57ff123752c5c8f704
ENV_REV=6de71edb142be36319e380ce782c3d1830c65d68
if [ ! -d "$DIR/.git" ]; then
  git clone --quiet https://github.com/riscv-software-src/riscv-tests "$DIR"
fi
git -C "$DIR" checkout --quiet "$TESTS_REV"
git -C "$DIR" submodule update --init --quiet env
git -C "$DIR/env" checkout --quiet "$ENV_REV"
echo "riscv-tests $TESTS_REV (env $ENV_REV) in $DIR"
