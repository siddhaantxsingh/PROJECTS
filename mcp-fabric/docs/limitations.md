# Limitations

* **Partial protocol coverage.**
  * Covered: `initialize`, `ping`, tools, resources and prompts, `list_changed`, and cancellation.
  * Not covered: sampling, elicitation, roots, logging, completion, resource subscriptions and progress notifications, which are not forwarded between clients and upstreams.
  * Replies are always single JSON responses, never SSE streams per request, so server-initiated requests inside a call are not supported.
* **OAuth tested only against an in-test IdP.** Token validation is tested with real RSA/EC keys served by an in-test JWKS server. It has not been run against Keycloak, Auth0 or Entra ID; see [OWNER_ACTIONS](OWNER_ACTIONS.md).
  * The gateway is only a resource server: it does not run the authorization-code flow, dynamic client registration or token exchange.
  * Opaque (non-JWT) tokens and introspection (RFC 7662) are not supported. Revocation therefore takes effect only when the JWT expires; keep access-token lifetimes short.
  * `aud` must contain the configured resource URI. IdPs that cannot put the resource indicator in `aud` need the `audiences` setting.
* **Approvals are in memory.** A pending call is lost if the gateway restarts, and the client gets a connection error.
  * A client's `notifications/cancelled` does not cancel a pending approval; it simply times out.
  * Approval rules are global, not per tenant.
  * There is no notification channel (email, Slack) for approvers: they poll `GET /admin/approvals`.
* **Tool screening is heuristic.** Pattern lists miss new phrasings and can flag legitimate text. Only `description` and `title` text is scanned; tool results (responses) are not scanned for injected instructions. Resources and prompts are not pinned or screened.
* **Per-call hash.** Each `tools/call` recomputes the SHA-256 of the tool's canonical definition to compare it with the pin. This cost was not isolated in the benchmark (see [benchmarks](benchmarks.md)).
* **No response-size limit from upstreams.** A large upstream reply is read whole into memory. Request bodies, by contrast, are limited.
* **TLS** is tested with a self-signed keystore. Certificate rotation needs a restart. mTLS to clients or upstreams is not implemented.
* **Single instance.** Sessions, rate-limit buckets and the circuit-breaker state are kept in memory. Restarting the gateway drops sessions, and clients must re-initialize.
* **Resource URIs are not namespaced.** If two upstreams list the same URI, the first one wins.
* **Per-call fsync.** The audit log syncs on every call with no group commit, which caps throughput at about 2,300 calls/s on this VM ([benchmarks](benchmarks.md)).
* **Test upstreams are the bundled demo server.** It is a real separate process speaking the protocol over stdio and HTTP. Third-party MCP servers were not tested.
