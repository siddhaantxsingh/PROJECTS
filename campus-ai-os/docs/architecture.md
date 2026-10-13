# Architecture

```
Browser (React SPA, same origin)
   │ cookie session (HttpOnly, SameSite=Lax) + Origin check on writes
   ▼
FastAPI (backend/campus/app.py) ── routes only; no authorization logic
   │
   ▼
Campus service (service.py) ── every operation checks: platform role, course membership/role, quotas, k-anonymity
   │                    │
   │                    └── study.py: flashcards, quizzes, research grouping, document comparison
   ▼
Groundwork engine (library) ── tenant = "course:<id>": parsing, page-bounded chunks, BM25/LSA, RM3, reranking,
   │                            grounded extractive answers, citation checks, injection filtering
   ▼
PostgreSQL + pgvector ── app_* tables (users, sessions, courses, memberships, documents, questions,
                          feedback, flashcards, quizzes, attempts, audit) + Groundwork tables
```

## Key decisions

- **Course = retrieval tenant.** Isolation is enforced twice: membership checks in the service and tenant scoping in the engine (a document id from another course is simply not in the index). [ADR 0001](decisions/0001-course-as-tenant.md)
- **Deterministic study tools.** Flashcards, quizzes, research and comparison are extraction and retrieval over the course's own text, each item citing a page. No LLM is required, so nothing is invented. [ADR 0002](decisions/0002-extractive-study-tools.md)
- **Privacy-first analytics.** Instructors see only aggregates with k = 5 distinct eligible students per figure. [ADR 0003](decisions/0003-k-anonymous-analytics.md)

## Invariants and where they are tested

| Invariant | Test |
|---|---|
| Non-members get 404 for every course resource; the admin role does not grant content access | `test_non_members_cannot_see_course` |
| Students cannot perform instructor actions; demoted faculty lose them immediately | `test_students_cannot_do_instructor_actions`, `test_c1_*` |
| Retrieval never crosses courses, even with foreign document ids | `test_courses_are_isolated_in_retrieval` |
| Quiz answers are never sent before an attempt | `test_quiz_hides_answers_and_grades` |
| Quotas hold under concurrency | `test_c4_*`, `test_c5_*` |
| Deleting a document removes its chunks, flashcards and quizzes quoting it | `test_delete_document_*`, `test_c7_*` |
| Deleting an account removes personal free text | `test_c3_*` |
| Course deletion is resumable after a crash and invisible while in progress | `test_course_deletion_is_complete_and_resumable` |

## Concurrency

Single process. One app DB connection and one engine connection, each with a lock. Per-course locks serialize uploads. Generation, comparison and grouping work run outside the engine lock. Multi-process deployment would need the per-course lock to become a PostgreSQL advisory lock, and the in-memory index to be shared (see limitations).
