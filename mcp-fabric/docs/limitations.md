# Limitations

* **Partial protocol coverage.**
  * Covered: `initialize`, `ping`, tools, resources and prompts, `list_changed`, and cancellation.
  * Not covered: sampling, elicitation, roots, logging, completion, resource subscriptions and progress notifications, which are not forwarded between clients and upstreams.
  * Replies are always single JSON responses, never SSE streams per request, so server-initiated requests inside a call are not supported.
* **No OAuth.** Clients authenticate with static bearer tokens, not the MCP authorization specification's OAuth 2.1 flow. There is no TLS: put the gateway behind a TLS-terminating proxy.
* **Single instance.** Sessions, rate-limit buckets and the circuit-breaker state are kept in memory. Restarting the gateway drops sessions, and clients must re-initialize.
* **Resource URIs are not namespaced.** If two upstreams list the same URI, the first one wins.
* **Per-call fsync.** The audit log syncs on every call with no group commit, which caps throughput at about 2,300 calls/s on this VM ([benchmarks](benchmarks.md)).
* **Test upstreams are the bundled demo server.** It is a real separate process speaking the protocol over stdio and HTTP. Third-party MCP servers were not tested.
