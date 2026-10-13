# ADR 0001 — Each course is a retrieval tenant
Status: accepted (2026-10-14)

Decision: every course maps to the Groundwork tenant `course:<uuid>`. Uploads, queries, deletions and comparisons go through that tenant only.

Why: course isolation then holds even if a service-layer check were missed (defence in depth), and per-course embedding models fit each course's vocabulary.

Consequences: a document shared by two courses is stored twice; cross-course search is not possible (deliberately).
