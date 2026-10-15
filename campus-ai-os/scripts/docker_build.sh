#!/usr/bin/env bash
# Build the image with Groundwork as a named build context; forwards a proxy CA if present.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=${1:-campus-ai-os:latest}
CA=${PROXY_CA:-/root/.ccr/ca-bundle.crt}
args=(--network host --build-context groundwork=../groundwork -t "$TAG")
[ -s "$CA" ] && args+=(--secret id=ca,src="$CA")
DOCKER_BUILDKIT=1 exec docker build "${args[@]}" .
