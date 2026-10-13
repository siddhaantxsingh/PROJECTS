# Deployment

Verified here: backend + built frontend served by uvicorn against PostgreSQL 16.15 + pgvector 0.6.0 (`scripts/e2e-server.sh`, used by the browser test). The Dockerfile and compose file were **not** built or run (Docker Hub unreachable from the build environment).

```bash
pip install -e ../groundwork -e backend
(cd frontend && npm ci && npm run build)
export CAMPUS_DATABASE_URL=postgresql://user:pass@db:5432/campus
export CAMPUS_ALLOWED_ORIGINS=https://campus.example.edu
export CAMPUS_BOOTSTRAP_ADMIN='admin@example.edu:<long random password>'   # first start only
export CAMPUS_STATIC_DIR=$PWD/frontend/dist CAMPUS_SECURE_COOKIES=1
cd backend && uvicorn --factory campus.app:create_app --host 0.0.0.0 --port 8000
```

Put TLS termination (nginx, Caddy, a load balancer) in front; the app expects to be the only origin. Backups: `pg_dump` of the database (it holds app data, documents' chunks and embeddings). Health: `GET /healthz`.

| Variable | Meaning |
|---|---|
| `CAMPUS_DATABASE_URL` | PostgreSQL with the `vector` extension |
| `CAMPUS_ALLOWED_ORIGINS` | origins allowed to make state-changing requests |
| `CAMPUS_BOOTSTRAP_ADMIN` | `email:password` for the first admin (ignored once an admin exists) |
| `CAMPUS_STATIC_DIR` | built frontend directory |
| `CAMPUS_SECURE_COOKIES` | `1` behind HTTPS |
| `CAMPUS_LOGIN_PER_MIN` | login/registration attempts per IP per minute (password changes: half) |
| `CAMPUS_DIM` | embedding dimension (default 256) |
