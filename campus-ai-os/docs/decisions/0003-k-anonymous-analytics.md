# ADR 0003 — k-anonymous instructor analytics
Status: accepted (2026-10-14)

Decision: instructors never see individual questions. Every analytics figure is shown only when at least k = 5 distinct eligible students contributed. Eligible students are those who joined the course more than one day before asking. Before k is reached, only the total question count is shown.

Why: students should be able to ask "naive" questions without being identifiable, and the audit showed that partial thresholds leak (C2).

Consequences: small courses see little analytics. The one-day rule slows sock-puppet attacks but does not stop a patient instructor who creates accounts in advance; stronger protection (differential privacy, verified enrolment) is future work.
