# Known limitations

1. **No real users yet.** The product has not been put in front of students or faculty; no usage, retention or satisfaction data exists. A beta plan is in `docs/beta-plan.md`.
2. **No LLM.** Answers are extractive (sentences from the material with citations); they are not paraphrased or synthesised across sources. Questions whose answer is not stated in a single sentence get partial answers or an abstention.
3. **Study tools depend on definitional text.** Flashcards need sentences like "X is a ..."; quizzes test recognition of defined terms only.
4. **Retrieval quality is that of Groundwork with offline embedders** (see its benchmarks: BM25 + RM3/reranking best; no neural embedder).
5. **Single process.** Per-course upload locks and the in-memory index are per process; multiple replicas need advisory locks and a shared index.
6. **Analytics privacy is k-anonymity with a membership-age rule**, not differential privacy; a patient instructor creating accounts in advance can still probe.
7. **Accounts:** no email verification, password reset, SSO/OIDC or MFA. Faculty roles are granted by an admin.
8. **Not deployed; Docker image not built; CI not run on GitHub** (Docker Hub and GitHub unreachable from the build environment).
9. **Browser support tested:** Chromium only (Playwright, headless).
