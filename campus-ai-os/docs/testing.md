# Testing

| Suite | Command | Last result |
|---|---|---|
| Backend API (real PostgreSQL + pgvector) | `cd backend && pytest` | 39 passed (`test-logs/pytest-2026-10-14.txt`) |
| Frontend type check + build | `cd frontend && npm run build` | ok |
| Browser E2E (Chromium, real server, fresh DB) | `cd frontend && npx playwright test` | 1 passed, 1 skipped (`docs/evidence/2026-10-15/e2e-default.log`) |
| Browser E2E with a local LLM (opt-in) | `cd frontend && CAMPUS_LLM_PROVIDER=llamacpp npx playwright test e2e/campus-llm.spec.ts` (needs `pip install -e '../groundwork[local]'` and `python ../groundwork/scripts/fetch_models.py`) | 1 passed in 4.2 min on a heavily loaded CPU (`docs/evidence/2026-10-15/e2e-llm.log`) |
| Docker image smoke test | `scripts/docker_build.sh campus-ai-os:test && scripts/docker_smoke.sh campus-ai-os:test` | SMOKE OK (`docs/evidence/2026-10-15/docker-smoke-default.log`) |

Backend tests cover the vertical slice (upload → grounded answer with page citation), abstention, RBAC and course isolation, CSRF, login limits, cookie flags, password change revocation, flashcards, quizzes (answers hidden, grading), research, comparison, feedback, quotas (also under concurrency), k-anonymous analytics, document/account/course deletion (including crash-resume), admin actions, invalid input, idempotent re-upload, and the ten audit regressions.

The E2E test drives the real UI: an admin promotes a registered user to faculty, who creates a course and uploads a document. A student registers, joins with the code, asks a question and gets a cited answer, gets an abstention on an off-topic question, browses flashcards and takes a quiz. The instructor's analytics stay hidden below k. Screenshots are in `docs/screenshots/`.
