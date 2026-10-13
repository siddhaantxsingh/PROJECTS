# Testing

| Suite | Command | Last result |
|---|---|---|
| Backend API (real PostgreSQL + pgvector) | `cd backend && pytest` | 39 passed (`test-logs/pytest-2026-10-14.txt`) |
| Frontend type check + build | `cd frontend && npm run build` | ok |
| Browser E2E (Chromium, real server, fresh DB) | `cd frontend && npx playwright test` | 1 passed (`test-logs/e2e-2026-10-14.txt`) |

Backend tests cover the vertical slice (upload → grounded answer with page citation), abstention, RBAC and course isolation, CSRF, login limits, cookie flags, password change revocation, flashcards, quizzes (answers hidden, grading), research, comparison, feedback, quotas (also under concurrency), k-anonymous analytics, document/account/course deletion (including crash-resume), admin actions, invalid input, idempotent re-upload, and the ten audit regressions.

The E2E test drives the real UI: an admin promotes a registered user to faculty, who creates a course and uploads a document. A student registers, joins with the code, asks a question and gets a cited answer, gets an abstention on an off-topic question, browses flashcards and takes a quiz. The instructor's analytics stay hidden below k. Screenshots are in `docs/screenshots/`.
