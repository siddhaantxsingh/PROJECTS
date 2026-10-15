# Requirements matrix — Project 14 (Campus AI OS)

| Requirement | Status | Evidence |
|---|---|---|
| Student/faculty accounts | VERIFIED | auth tests; E2E registration/promotion |
| Course workspaces | VERIFIED | course tests; tenant isolation |
| Document uploads | VERIFIED | PDF/MD upload tests; E2E upload |
| RBAC | VERIFIED | `test_students_cannot_do_instructor_actions`, `test_non_members_cannot_see_course`, C1 |
| Hybrid retrieval | VERIFIED (via Groundwork) | Groundwork benchmarks; service uses BM25+rerank for Ask, RM3+rerank for Research |
| Reranking | VERIFIED | Groundwork feature reranker |
| Citation-grounded answers | VERIFIED | `test_vertical_slice_*`; E2E |
| PDF page references | VERIFIED | `test_pdf_page_reference` |
| Research mode | VERIFIED | `test_research_mode_groups_by_document` |
| Study mode | VERIFIED | flashcards + quizzes tests; E2E |
| Flashcards | VERIFIED | `test_flashcards_extracted_with_citations` |
| Quizzes | VERIFIED | `test_quiz_hides_answers_and_grades`, C6, C7, C9 |
| Document comparison | VERIFIED | `test_compare_documents`, C8 |
| Usage analytics | VERIFIED | k-anonymous analytics tests, C2 |
| Quotas | VERIFIED | quota tests incl. concurrency (C4, C5) |
| Data deletion | VERIFIED | document/account/course deletion tests, C3, C7 |
| Administration | VERIFIED | admin tests; E2E role change |
| Feedback collection | VERIFIED | `test_feedback_only_on_own_questions`; E2E |
| Minimum vertical slice first | VERIFIED | upload → question → grounded cited answer (E2E) |
| Beta for students/faculty | NOT IMPLEMENTED | plan only (`docs/beta-plan.md`); requires owner |
| Real users / engagement | NOT IMPLEMENTED | none exist; not fabricated |
| LLM-generated answers | VERIFIED (local small model) | `CAMPUS_LLM_PROVIDER=llamacpp` (Qwen2.5-0.5B-Instruct via Groundwork's `LlamaCppProvider` + grounding enforcement); browser E2E `e2e/campus-llm.spec.ts` passed (`docs/evidence/2026-10-15/e2e-llm.log`, `docs/screenshots/ask-llm.png`); API check `docs/evidence/2026-10-15/api-llm.log`. Quality of a 0.5B model is low (see Groundwork benchmarks); extractive stays the default |
| Containerised deployment | VERIFIED (image + smoke test) | `scripts/docker_build.sh`, `scripts/docker_smoke.sh`; `docs/evidence/2026-10-15/docker-build.log`, `docker-smoke-default.log`. Not deployed anywhere |
| CI | VERIFIED | `../.github/workflows/campus-ai-os.yml` green on GitHub Actions (run 38478453632), incl. Playwright E2E |
| LICENSE | BLOCKED | owner decision |
