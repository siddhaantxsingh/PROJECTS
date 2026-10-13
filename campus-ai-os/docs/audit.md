# Security and privacy audit (2026-10-14)

An independent adversarial review (separate agent, read-only access, every claim reproduced with a script against a live server and database) found 10 defects. All are fixed; `backend/tests/test_audit_regressions.py` has a test per defect.

Evidence of failure before the fix: the reviewer's reproductions (outputs quoted below) ran against the pre-fix code. The regression tests were written after the fixes and were not re-run against the pre-fix code, because that code was not committed separately (process gap noted; later projects commit before fixing).

| ID | Severity | Defect (reproduced) | Fix | Test |
|---|---|---|---|---|
| C1 | high | a faculty member demoted to student kept instructor rights (upload 201, analytics 200) | instructor actions need membership **and** a faculty/admin platform role; demotion converts memberships; endpoint to remove an instructor (never the last) | `test_c1_*` |
| C2 | high | analytics exposed single students (per-day counts, cited documents, average rating of one rater); 4 fresh sock-puppet accounts pushed one student's topic over k=5 | every breakdown needs ≥ k distinct eligible students; nothing but the total before that; eligible = member > 1 day before asking; instructors excluded | `test_c2_*` |
| C3 | medium | account deletion kept feedback comments and question text ("my phone is 555-1234") | feedback deleted, question text erased, only anonymous counts kept | `test_c3_*` |
| C4 | medium | 20 parallel questions with quota 1 recorded 4 | quota check + reservation in one transaction with a row lock on the user, before retrieval | `test_c4_*` |
| C5 | medium | 10 parallel uploads ended at 2× the storage quota | check–ingest–record serialized per course | `test_c5_*` |
| C6 | medium | instructor-made quizzes vanished (404) when that instructor deleted their account; course left with no instructor | `official` stored at creation; the last instructor of an active course cannot delete their account | `test_c6_*` |
| C7 | low-medium | quiz JSON kept sentences of a deleted document | deleting a document deletes quizzes quoting it | `test_c7_*` |
| C8 | medium | compare built an |A|×|B| matrix under the global engine lock (2.3 s, blocking all queries; ~12 GB at the size limit by extrapolation) | snapshot under the lock, compute outside, blockwise, ≤ 2000 chunks per side (evenly sampled, reported as `sampled`) | `test_c8_*` |
| C9 | low | quiz prompt left later occurrences of the answer and matched inside words ("_____n array") | blank every whole-word occurrence | `test_c9_*` |
| C10 | low | register / password change / account deletion (scrypt) unlimited | per-IP and per-user limits | `test_c10_*` |

Reviewer checks that found no defect: CSRF (missing/`null` Origin, look-alike Referers), SPA path traversal (`../`, `%2e%2e`), XSS sinks in the React UI, cross-course document access, session fixation, quiz-answer exposure before an attempt.

A second defect class was found by the browser E2E test, in the Groundwork dependency: inconsistent stemming ("resolved" vs "resolves") made the answer to "How are collisions resolved?" miss the right sentence. Fixed in Groundwork `163ef4a` with a regression test.
