# Known limitations

1. **No real users yet.** The product has not been put in front of students or faculty; no usage, retention or satisfaction data exists. A beta plan is in `docs/beta-plan.md`.
2. **LLM answers are opt-in and come from a very small model.** By default answers are extractive. With `CAMPUS_LLM_PROVIDER=llamacpp`, Qwen2.5-0.5B-Instruct writes the answer and every sentence is checked against its cited source; unsupported sentences are removed and the answer abstains if nothing is left. In Groundwork's benchmark this model abstained on 65 % of queries after enforcement. On the shared CPU used here one answer took up to 333 s depending on machine load (`docs/evidence/2026-10-15/api-llm.log`, load average about 23–40). The model is loaded in the server process (about 1 GB RAM).
3. **Study tools depend on definitional text.** Flashcards need sentences like "X is a ..."; quizzes test recognition of defined terms only.
4. **Retrieval quality is that of Groundwork.** The default uses offline embedders; `CAMPUS_EMBEDDER=nomic` (with `CAMPUS_DIM=768` on a new database) enables nomic-embed-text, which was benchmarked in Groundwork but not run end to end in Campus.
5. **Single process.** Per-course upload locks and the in-memory index are per process; multiple replicas need advisory locks and a shared index.
6. **Analytics privacy is k-anonymity with a membership-age rule**, not differential privacy; a patient instructor creating accounts in advance can still probe.
7. **Accounts:** no email verification, password reset, SSO/OIDC or MFA. Faculty roles are granted by an admin.
8. **Not deployed.** The Docker image was built and smoke-tested on 2026-10-15 against host PostgreSQL (no LLM inside the image). CI runs on GitHub Actions; the LLM browser test is opt-in and not part of CI.
9. **Browser support tested:** Chromium only (Playwright, headless).
