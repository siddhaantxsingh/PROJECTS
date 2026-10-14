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

## Security notes

* Client and admin tokens are configured as SHA-256 digests and compared with `MessageDigest.isEqual`. Every client entry is examined on each authentication.
* Sessions are bound to the client that created them.
* Upstream credentials (`headers`, `env`) live only in the server configuration and never reach clients.
