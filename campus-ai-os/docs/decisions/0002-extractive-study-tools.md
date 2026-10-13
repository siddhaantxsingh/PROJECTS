# ADR 0002 — Extractive, citable study tools
Status: accepted (2026-10-14)

Decision: flashcards come from definitional sentences, quizzes are cloze questions over those definitions with distractors from other terms, research groups evidence sentences by document, and comparison uses idf-weighted key terms plus section alignment. Every item carries a page and section.

Why: no LLM was available in the build environment, and for a study product a wrong generated flashcard is worse than a missing one. Extraction is deterministic, cheap, and traceable.

Consequences: coverage depends on how definitional the material is (a lecture with few "X is a Y" sentences yields few flashcards); questions test recall of definitions only. An LLM provider can be added behind the Groundwork provider interface later, with the same grounding checks.
