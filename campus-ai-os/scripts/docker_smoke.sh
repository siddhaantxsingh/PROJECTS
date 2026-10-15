#!/usr/bin/env bash
# Smoke test of a built image against host PostgreSQL+pgvector (host network):
# serves the SPA, admin logs in, creates a course, uploads a lecture, asks a
# question and checks the cited answer.
#   scripts/docker_smoke.sh [IMAGE] [extra docker run args, e.g. -e CAMPUS_LLM_PROVIDER=llamacpp -v /models:/models:ro]
set -euo pipefail
cd "$(dirname "$0")/.."
IMG=${1:-campus-ai-os:test}; shift || true
PORT=${CAMPUS_SMOKE_PORT:-18739}; DB=campus_smoke_$$
psql -h 127.0.0.1 -U postgres -qc "CREATE DATABASE $DB"
cleanup() { docker logs "$DB" > /tmp/$DB.log 2>&1 || true; docker rm -f "$DB" >/dev/null 2>&1 || true; psql -h 127.0.0.1 -U postgres -qc "DROP DATABASE IF EXISTS $DB WITH (FORCE)"; }
trap cleanup EXIT
docker run -d --name "$DB" --network host --read-only --tmpfs /tmp "$@" \
  -e CAMPUS_DATABASE_URL="postgresql://postgres@127.0.0.1:5432/$DB" -e CAMPUS_ALLOWED_ORIGINS="http://127.0.0.1:$PORT" \
  -e CAMPUS_BOOTSTRAP_ADMIN="admin@uni.edu:admin-password-123" "$IMG" \
  uvicorn --factory campus.app:create_app --host 127.0.0.1 --port "$PORT" >/dev/null
for i in $(seq 1 120); do curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break; sleep 2; done
curl -fsS "http://127.0.0.1:$PORT/healthz"; echo
curl -fsS "http://127.0.0.1:$PORT/" | grep -q '<div id="root">' && echo "SPA served"
python3 - "$PORT" <<'PY'
import json, sys, httpx
base = f"http://127.0.0.1:{sys.argv[1]}"
c = httpx.Client(base_url=base, headers={"Origin": base}, timeout=900)
c.post("/api/auth/login", json={"email": "admin@uni.edu", "password": "admin-password-123"}).raise_for_status()
course = c.post("/api/courses", json={"code": "CS201", "title": "Data Structures"}).json()
up = c.post(f"/api/courses/{course['id']}/documents", files={"file": ("lecture.md", open("frontend/e2e/lecture.md", "rb"), "text/markdown")})
up.raise_for_status()
a = c.post(f"/api/courses/{course['id']}/ask", json={"query": "How are hash collisions resolved?"}).json()
print(json.dumps({k: a.get(k) for k in ("answer", "provider", "abstained", "citations", "grounding")}, indent=1))
print("sources:", [(s["n"], s["document"], s["pages"]) for s in a["sources"]])
assert not a["abstained"] and a["citations"] and "chaining" in a["answer"].lower()
PY
echo "SMOKE OK ($IMG)"
