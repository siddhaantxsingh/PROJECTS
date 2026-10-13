# Security

Report vulnerabilities privately to siddhaantxsingh@gmail.com.

## Threat model summary

| Threat | Control | Test |
|---|---|---|
| Cross-course access (IDOR) | membership check on every operation + retrieval tenant per course; foreign ids → 404 | `test_non_members_cannot_see_course`, `test_courses_are_isolated_in_retrieval` |
| Privilege escalation | self-registration is student-only; faculty granted by admin; instructor actions need membership and platform role | `test_self_registration_cannot_choose_role`, `test_c1_*` |
| CSRF | SameSite=Lax cookie + exact Origin/Referer allow-list on every non-GET API call | `test_csrf_origin_and_auth_required` |
| Session theft impact | only SHA-256 of tokens stored; HttpOnly cookie; 7-day expiry; revoked on logout, password change, disable | `test_session_cookie_flags_and_logout`, `test_password_change_revokes_sessions` |
| Password attacks | scrypt (N=2^15); constant-time compare; dummy hash for unknown emails; rate limits on login, register, password change, account deletion | `test_login_errors_and_rate_limit`, `test_c10_*` |
| Student privacy vs instructors | k-anonymous aggregates only, eligibility by membership age | `test_c2_*`, `test_analytics_are_aggregated_and_k_anonymous` |
| Right to deletion | account, document and course deletion remove content (including quiz copies and free text) | `test_c3_*`, `test_c7_*`, deletion tests |
| Prompt injection in uploaded material | Groundwork excludes flagged chunks; extractive answers cannot follow instructions | Groundwork `docs/security.md` |
| XSS | React escaping only; no raw HTML rendering; CSP `default-src 'self'` | reviewer check |
| Resource exhaustion | upload size, storage and question quotas (atomic), compare caps, query length limits | `test_c4/5/8_*`, `test_storage_quota` |

Full audit: [docs/audit.md](docs/audit.md). Limitations: [docs/limitations.md](docs/limitations.md).
