# Deployment

## Requirements

PostgreSQL 15+ with the `vector` extension (pgvector ≥ 0.5 for HNSW), Redis 6+ (optional), Python 3.11+.

## Run locally (verified in the build sandbox: PostgreSQL 16.15 + pgvector 0.6.0, Redis 7.0)

```bash
pip install -e '.[dev]'
export GW_DATABASE_URL=postgresql://postgres@127.0.0.1:5432/groundwork
export GW_REDIS_URL=redis://127.0.0.1:6379/0        # optional
export GW_API_KEYS="acme:$(openssl rand -hex 24)"   # tenant:key,...
python -m groundwork migrate
python -m groundwork serve --host 0.0.0.0 --port 8080
curl -H "Authorization: Bearer <key>" -F file=@manual.pdf localhost:8080/v1/documents
curl -H "Authorization: Bearer <key>" -H 'content-type: application/json' \
     -d '{"query":"How often is the gearbox lubricated?"}' localhost:8080/v1/query
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `GW_DATABASE_URL` | — | PostgreSQL URL (required) |
| `GW_API_KEYS` | — | `tenant:key` pairs, keys ≥ 16 chars |
| `GW_REDIS_URL` | unset | cache + cluster-wide rate limits |
| `GW_RATE_PER_MIN` | 120 | requests per minute per key |
| `GW_DIM` | 256 | embedding column dimension (fixed per database) |
| `GW_LLM_BASE_URL`, `GW_LLM_API_KEY`, `GW_LLM_MODEL`, `GW_LLM_PRICE_IN/OUT` | unset | OpenAI-compatible provider (not exercised against a real model here) |

## Container

`Dockerfile` and `docker-compose.yml` are provided. **They were not built or run in the build sandbox** (Docker Hub is unreachable there). The compose file uses the official `pgvector/pgvector:pg16` and `redis:7` images.

## Operations

- Health: `GET /healthz` (503 if PostgreSQL is down; Redis degradation reported but not fatal).
- Metrics: `GET /metrics` (Prometheus text: request counts and latency sums per route, index sizes, cache errors, rate-limit fallbacks).
- Backup: standard `pg_dump` of the database; Redis holds only disposable cache/limit state.
- Changing `GW_DIM` requires a new database or a re-embed (`migrate` refuses a mismatched dim).
