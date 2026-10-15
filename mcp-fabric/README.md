# MCP Fabric (project 15)

A **Model Context Protocol gateway**, built with Java 21 and Spring Boot 3. One MCP endpoint federates many upstream MCP servers: local processes over stdio, and remote servers over Streamable HTTP. It adds the controls a team needs before giving agents access to tools:

* **OAuth 2.1 resource server** per the MCP authorization spec: JWT access tokens checked against the IdP's JWKS (cached, rotation-aware), `iss`, `aud` (resource indicator), `exp`/`nbf`, required scopes; `WWW-Authenticate` with `resource_metadata`; Protected Resource Metadata at `/.well-known/oauth-protected-resource`. Static tokens remain as an opt-in dev mode;
* least-privilege policy: per-tenant allow/deny lists over tools, narrowed further by `mcp:server:<name>` scopes;
* **human approvals** for sensitive tools (calls wait until an admin approves or denies, with timeout and audit);
* **credential isolation**: upstream credentials come from config/env; the client's token is never forwarded; stdio children get a scrubbed environment;
* **tool-poisoning and rug-pull defence**: suspicious tool metadata is redacted, tool definitions are pinned by hash and quarantined when they change;
* path-traversal checks on declared file arguments, request-size limits, and TLS served directly;
* JSON Schema validation of tool arguments before anything reaches an upstream;
* per-client rate limits;
* timeouts with cancellation forwarded upstream;
* circuit breakers and automatic restart of crashed stdio servers;
* a tamper-evident, hash-chained audit log;
* Prometheus metrics.

**Status:** 33 tests pass locally and in CI (run 38740636150), plus one opt-in benchmark. The integration tests drive real upstream processes over both transports. They also cover OAuth with real RSA/EC keys served by an in-test JWKS server, and HTTPS with a generated keystore. These features have only been tested against that in-test IdP, not a real Keycloak or Auth0 ([owner actions](docs/OWNER_ACTIONS.md)). Not implemented: high availability and some protocol features ([limitations](docs/limitations.md)).

## How it looks to a client

```
initialize                     → session (Mcp-Session-Id), protocol 2025-06-18 or 2025-03-26
tools/list                     → files__read, files__write, search__query, … (only what this client may call)
tools/call files__read {...}   → validated, authorised, rate limited, forwarded to the "files" server, audited
resources/*, prompts/*         → routed to the owning upstream
GET /mcp (SSE)                 → notifications/tools/list_changed when any upstream's catalog changes
```

## Quick start

Development mode uses static tokens (`fabric.auth.mode: static` in `config/example.yml`). Without that line the gateway starts in OAuth mode and refuses to start until an issuer is configured; see `config/oauth-example.yml`.

```sh
mvn -B package
java -jar target/mcp-fabric-0.1.0.jar --spring.config.additional-location=config/example.yml
curl -s -X POST localhost:8090/mcp -H 'Authorization: Bearer change-me-ide' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' -i
```

`config/example.yml` documents every setting. Tokens are configured as SHA-256 digests: `printf %s TOKEN | sha256sum`.

## Security model in one paragraph

A client gets a JWT from your IdP for resource `https://<host>/mcp` with scope `mcp:tools` and one or more `mcp:server:<upstream>` scopes. The token also carries a `tenant` claim. The gateway checks the signature (asymmetric algorithms only), issuer, audience, expiry and scope. It then applies the tenant's allow/deny list, narrowed to the upstreams the scopes name. Tools matching `fabric.approval.required-for` wait for a person holding `mcp:admin` to approve them (`POST /admin/approvals/{id}/approve`). The person who made the call cannot approve it. Upstreams get their own credentials from config/env, never the client's token. Tool metadata from untrusted upstreams is screened for injected instructions and pinned by hash. Every decision is written to the hash-chained audit log. Details: [design](docs/design.md#security).

## Measured

On a 4-vCPU VM, with the gateway, client and upstream sharing the machine (measured on 2026-10-14, before the security features were added; see [benchmarks](docs/benchmarks.md) for a re-run under heavy shared load that could not separate the new code's cost from noise):

* The gateway adds **~2.3 ms at p50** per tool call.
* It sustains **2,255 calls/s** with every call fsynced to the audit log, or **5,815 calls/s** with the log on tmpfs, which shows the fsync is the limit. See [docs/benchmarks.md](docs/benchmarks.md).

## Documentation

* [Design](docs/design.md): protocol handling, the tool-call pipeline, upstream transports, the circuit breaker, the audit chain and security notes.
* [Testing](docs/testing.md).
* [Benchmarks](docs/benchmarks.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
* [Owner actions](docs/OWNER_ACTIONS.md): real IdP (Keycloak/Auth0) and TLS setup.
* [Scorecard](docs/scorecard.md).
