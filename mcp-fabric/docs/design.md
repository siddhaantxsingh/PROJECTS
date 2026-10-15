# Design

```
 MCP clients ──Streamable HTTP──► mcp-fabric ──stdio──► upstream MCP server (child process)
 (IDEs, agents)  POST/GET/DELETE /mcp   │       └─HTTP───► upstream MCP server (remote)
                                        ├─ policy: bearer auth, allow/deny, token buckets
                                        ├─ JSON Schema validation of tool arguments
                                        ├─ audit log (hash chain, fsync)
                                        └─ /actuator/prometheus, /admin/*
```

## Protocol

* **Transport.** MCP over Streamable HTTP; revisions 2025-06-18 and 2025-03-26 are negotiated.
  * `POST /mcp` carries one JSON-RPC message. Requests get a JSON reply; notifications get `202`.
  * `GET /mcp` opens a server-sent-event stream for the session.
  * `DELETE /mcp` ends the session.
* **Sessions.** `initialize` creates a session (`Mcp-Session-Id`). Later calls need that id, and it must belong to the authenticated client.
* **Rejections.**
  * An unsupported `MCP-Protocol-Version` header gets `400`.
  * JSON-RPC batches get `400`; the 2025-06-18 revision removed them.
* **Federation.**
  * Upstream `u`'s tools and prompts appear as `u__name`.
  * Resources keep their URIs and are routed to the upstream that lists them.
  * Lists are merged, paginated with opaque cursors, and filtered per client: unauthorised tools are not listed.
* **Change notifications.** When an upstream sends `notifications/tools/list_changed` (or the resources/prompts variants), the fabric reloads that upstream's catalog. It then sends `notifications/tools/list_changed` on every open client stream.

## Tool calls

1. **Authorize.** Deny globs win over allow globs. Failure: error `-32001`.
2. **Rate limit.** A token bucket per client (rate and burst). Failure: HTTP `429` with error `-32029`. Only `tools/call` is limited.
3. **Validate.** The arguments are checked against the tool's `inputSchema` (JSON Schema 2020-12, networknt), before anything is sent upstream. Failure: error `-32602`, with the validation messages in `data`.
4. **Forward** with the upstream's timeout.
   * On a timeout the fabric sends `notifications/cancelled` upstream and answers `-32002`.
   * A client's `notifications/cancelled` for an in-flight call is translated to the upstream's request id and forwarded.
   * A `-32002` also covers an unreachable upstream or an open circuit.
5. **Audit.** One record per call, whether it succeeded or was rejected: client, session, tool, the SHA-256 of the canonical arguments (never the arguments themselves), outcome and latency.

## Upstreams

* **stdio.** The server is a child process, speaking newline-delimited JSON-RPC. If the process exits, every outstanding request fails at once and the process is restarted with exponential backoff (1 s to 30 s), followed by a new handshake.
* **HTTP.** Streamable HTTP as a client. The session id is kept. A `404` (the session expired) triggers a reconnect. A GET stream, if the server offers one, delivers its notifications.
* **Circuit breaker.**
  * After `threshold` consecutive failures (timeouts or transport errors) the circuit opens and calls fail immediately.
  * After `cooldown`, a single trial call is allowed: success closes the circuit, failure reopens it.
  * Tool errors (`isError: true`) and JSON-RPC errors returned by the upstream count as successes: the upstream answered.

## Audit log

* **Format.** JSON lines. Each record holds `seq`, `ts`, the previous record's hash, and its own SHA-256 over all of that.
* **Durability.** Each write is followed by `FileChannel.force` before the response.
* **Verification.** `GET /admin/audit/verify` (admin token) walks the chain. An edited, deleted or reordered record is reported, along with its position.
* **Crash recovery.** On start, a torn last line is truncated and the chain continues.

## Security

### Authentication (OAuth 2.1 resource server)

`fabric.auth.mode: oauth` is the default. The gateway is a resource server as described in the MCP authorization spec (2025-06-18). It does not issue tokens; your IdP does.

* **Token checks** (`auth/JwtVerifier`, Nimbus JOSE+JWT):
  * The signature is checked against the issuer's JWKS. Keys are cached (`jwks-cache-ttl`, default 5 min). An unknown `kid` triggers a re-fetch, so key rotation works without a restart. Re-fetches are rate-limited (`jwks-min-refresh-interval`) so junk tokens cannot flood the IdP.
  * Only the configured asymmetric algorithms are accepted (default RS256, ES256, PS256). HMAC algorithms cannot be configured. `alg=none` and HS256-signed-with-the-public-key are rejected before any key lookup.
  * `iss` must match exactly. `aud` must contain the resource URI (the RFC 8707 resource indicator). `exp` and `sub` are required. `exp`/`nbf` allow `clock-skew` (default 30 s). `typ` must be `JWT`, `at+jwt` or absent.
  * Scopes come from `scope` (space-separated) or `scp` (array).
* **Responses.**
  * A missing or invalid token gets `401`, with `WWW-Authenticate: Bearer realm="mcp-fabric", error="invalid_token", error_description="<reason>", resource_metadata="<url>"`. The reason is coarse: `expired`, `audience`, `issuer`, `unknown_key`, `signature`, `alg_none`, `algorithm_not_allowed` or `malformed`.
  * A missing scope gets `403` with `error="insufficient_scope", scope="mcp:tools"`.
  * Tokens are read only from the `Authorization` header, never from the query string.
* **Protected Resource Metadata** (RFC 9728) is served at `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`. It lists `resource`, `authorization_servers`, `scopes_supported` and `bearer_methods_supported`.
* **Policy mapping (least privilege).**
  * The `tenant` claim selects a configured tenant: its allow/deny globs and rate limit. An unknown tenant gets `403`.
  * `mcp:server:<upstream>` scopes then narrow the tenant's tools to those upstreams (`mcp:server:*` grants all of them). Scopes can only narrow the tenant's list, never widen it.
  * The rate-limit bucket is per `tenant/client_id`.
  * A session is bound to `tenant/client_id` plus `sub`. Each request may present a fresh token for the same principal.
* **Admin API** (`/admin/*`) needs a token with `mcp:admin`. That scope does not open `/mcp`, and `mcp:tools` does not open `/admin`.
* **Static mode** (`mode: static`) keeps the earlier SHA-256-hashed shared tokens and admin key, for development only. A warning is logged at start.

### Human approvals

* Tools that match `fabric.approval.required-for` are checked (policy, schema, path guard) and then parked as `PENDING`. The client's HTTP request waits; requests run on virtual threads.
* An admin can list pending calls with `GET /admin/approvals`. The list shows the tool, the caller and the arguments, truncated to 4 KB; this is the only place arguments are shown, and the audit log keeps only their hash.
* An admin decides with `POST /admin/approvals/{id}/approve` or `/deny` (optional `{"reason": ...}`).
  * Repeating the same decision returns `ALREADY`.
  * An opposite decision, or any decision after expiry, gets `409`.
  * The caller cannot approve their own call (`403`).
* An approved call is forwarded. A denied call gets `-32001`. If nobody decides within `fabric.approval.timeout`, the call gets `-32003`.
* The audit log records `approval_requested`, `approval_decided` (with the approver), `approval_expired`, and the call itself with `approval` and `approved_by`.

### Credential isolation

* The gateway builds every upstream request itself. The client's `Authorization` header (and any other client header) is never copied to an upstream: token passthrough is forbidden by the MCP spec.
* HTTP upstreams get only their configured `headers`, for example `Authorization: Bearer ${SEARCH_UPSTREAM_TOKEN}` resolved from the gateway's environment.
* stdio upstreams get a scrubbed environment: only `PATH`, `HOME`, `LANG`, `LC_ALL`, `TZ`, `TMPDIR`, `USER` and `JAVA_HOME` (plus `SYSTEMROOT`/`WINDIR`), and their own `env`. Secrets meant for the gateway or other upstreams therefore do not leak into them. `inherit-env: true` restores the old behaviour.
* `SecurityIntegrationTest` records every byte both upstreams receive and checks that none of the 30+ client tokens issued during the test appears there.

### Tool metadata (poisoning and rug pulls)

* **Screening.** `guard/ToolGuard` scans every `description` and `title` in a tool definition, including inside `inputSchema`. It looks for:
  * hidden `<IMPORTANT>`/`<system>` tags;
  * "ignore previous instructions";
  * "do not tell the user";
  * "before using this tool";
  * references to credentials (`~/.ssh`, `id_rsa`, `.env`, API keys);
  * mentions of the system prompt;
  * HTML comments;
  * invisible Unicode (zero-width, bidi controls, tag characters);
  * oversized descriptions.
* **Actions.** Invisible characters are always stripped. For untrusted upstreams (the default) a flagged text is replaced (`redact`), or the tool is hidden (`block`). For `trusted: true` upstreams the finding is only reported. Findings are audited and listed at `GET /admin/tools`.
* **Pinning.** The SHA-256 of each tool's canonical JSON is pinned the first time it is seen, in memory or in `tool-guard.pins-file` so pins survive restarts. If an upstream later changes a pinned definition:
  * the tool is quarantined: hidden from `tools/list`, and calls get `-32001`;
  * `tool_definition_changed` is audited;
  * the tool stays quarantined until an admin calls `POST /admin/tools/{name}/accept`.
* **Limits.** The screening is pattern-based. It raises the cost of known attacks; it does not prove a description is safe. Pinning catches any change after first sight, but not a tool that was malicious from the start.

### Other input limits

* **Request size.** Request bodies over `fabric.max-request-bytes` (default 1 MiB) get `413`. This holds whether or not a Content-Length is declared.
* **JSON nesting.** JSON nested deeper than Jackson's default limit (1,000 levels) gets `400`.
* **Path arguments.** Arguments listed in an upstream's `path-arguments` are rejected if they:
  * contain NUL;
  * contain a `..` segment (also when percent-encoded or written with backslashes);
  * resolve outside `path-roots`.

  The check runs before the call is forwarded.

### TLS

Spring Boot `server.ssl` serves HTTPS directly; see `config/oauth-example.yml` and [OWNER_ACTIONS](OWNER_ACTIONS.md). `TlsTest` generates a keystore with `keytool` and runs an MCP handshake over TLS 1.3. In that test a client that does not trust the certificate is refused, and plain HTTP sent to the TLS port gets no MCP response.

### Earlier notes

* Static-mode client and admin tokens are configured as SHA-256 digests and compared with `MessageDigest.isEqual`. Every client entry is examined on each authentication.
* Upstream credentials (`headers`, `env`) live only in the server configuration and never reach clients.
