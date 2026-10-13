#!/usr/bin/env bash
# Starts the backend on a fresh database for browser E2E tests.
set -euo pipefail
cd "$(dirname "$0")/.."
ADMIN=${CAMPUS_ADMIN_URL:-postgresql://postgres@127.0.0.1:5432/postgres}
DB=campus_e2e
psql "$ADMIN" -qc "DROP DATABASE IF EXISTS $DB WITH (FORCE)" -c "CREATE DATABASE $DB"
export CAMPUS_DATABASE_URL="${ADMIN%/*}/$DB" CAMPUS_ALLOWED_ORIGINS="http://127.0.0.1:8123" \
  CAMPUS_BOOTSTRAP_ADMIN="admin@uni.edu:admin-password-123" CAMPUS_STATIC_DIR="$PWD/frontend/dist" CAMPUS_DIM=64
cd backend && exec python3 -m uvicorn --factory campus.app:create_app --host 127.0.0.1 --port 8123
