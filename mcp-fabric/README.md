# MCP Fabric (project 15)

A **Model Context Protocol gateway**, built with Java 21 and Spring Boot 3. One MCP endpoint federates many upstream MCP servers: local processes over stdio, and remote servers over Streamable HTTP. It adds the controls a team needs before giving agents access to tools:

* per-client tokens and allow/deny lists over tools;
* JSON Schema validation of tool arguments before anything reaches an upstream;
* per-client rate limits;
* timeouts with cancellation forwarded upstream;
* circuit breakers and automatic restart of crashed stdio servers;
* a tamper-evident, hash-chained audit log;
* Prometheus metrics.

**Status: done.** All 14 tests pass; the integration tests drive real upstream processes over both transports. The benchmarks were measured on this machine. Not implemented: OAuth, TLS, high availability, and some protocol features ([limitations](docs/limitations.md)).

## How it looks to a client

```
initialize                     → session (Mcp-Session-Id), protocol 2025-06-18 or 2025-03-26
tools/list                     → files__read, files__write, search__query, … (only what this client may call)
tools/call files__read {...}   → validated, authorised, rate limited, forwarded to the "files" server, audited
resources/*, prompts/*         → routed to the owning upstream
GET /mcp (SSE)                 → notifications/tools/list_changed when any upstream's catalog changes
```

## Quick start

```sh
mvn -B package
java -jar target/mcp-fabric-0.1.0.jar --spring.config.additional-location=config/example.yml
curl -s -X POST localhost:8090/mcp -H 'Authorization: Bearer change-me-ide' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' -i
```

`config/example.yml` documents every setting. Tokens are configured as SHA-256 digests: `printf %s TOKEN | sha256sum`.

## Measured

On a 4-vCPU VM, with the gateway, client and upstream sharing the machine:

* The gateway adds **~2.3 ms at p50** per tool call.
* It sustains **2,255 calls/s** with every call fsynced to the audit log, or **5,815 calls/s** with the log on tmpfs, which shows the fsync is the limit. See [docs/benchmarks.md](docs/benchmarks.md).

## Documentation

* [Design](docs/design.md): protocol handling, the tool-call pipeline, upstream transports, the circuit breaker, the audit chain and security notes.
* [Testing](docs/testing.md).
* [Benchmarks](docs/benchmarks.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
