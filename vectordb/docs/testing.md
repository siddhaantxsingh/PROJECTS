# Testing

Run everything with `cargo test` (the test profile builds at `opt-level = 2`, so the property and crash tests stay fast).

| Suite | Count | What it covers |
|---|---|---|
| unit tests in `src/` | 17 | SIMD kernels vs scalar reference (every length 0..300) and metric semantics; filter JSON round-trip, int/float equality, validation limits; HNSW recall vs exact with invariants, determinism for a seed, allow-predicate never violated with full result lists, empty/single graphs; PQ ADC equals distance to reconstruction, rerank recall and memory, bad `m`; WAL round trip, truncation at every byte, zero-filled tail, mid-log corruption |
| `tests/collection.rs` | 9 | end-to-end on `Collection`: CRUD; input validation and the directory lock; recovery from WAL and from snapshot gives identical results; **crash at every WAL byte offset** recovers exactly the acknowledged prefix; corrupt snapshot fails the open; rebuild/compaction; PQ persistence; a **proptest** of the filtered-search contract (results match exact filtered search, never contain deleted or non-matching ids); concurrent writers and readers |
| `tests/api.rs` | 2 | a real axum + tonic server on ephemeral ports: REST lifecycle and error codes (409 conflicting create, 422 bad name / wrong dimension, 404s, 204 deletes), filters, `/metrics`, snapshot, drop; gRPC round trip, `INVALID_ARGUMENT` for bad filter JSON, a second registry on a live data directory is refused, graceful shutdown and **restart recovery** |
| `tests/audit.rs` | 7 | regression tests for the audit findings (see [audit.md](audit.md)) |
| `tests/audit_pq_rerank.rs` | 1 | unbounded PQ `rerank` (separate binary: before the fix it aborted the process) |

Logs of the runs recorded in this repository are in `test-logs/`.

## Other checks

* `cargo clippy --all-targets -- -D warnings` and `cargo fmt --check` are clean.
* `vectordb check --data DIR` re-runs `Collection::check()` (graph invariants, store/graph/PQ sizes, live counts) on every collection after recovery.
* The benchmark harness asserts graph invariants after the build and that search results are identical before and after recovery.
* CI (`.github/workflows/vectordb.yml`) runs build, tests, fmt, clippy, a small benchmark as a smoke test, and a build on the minimum supported Rust version (1.89).

## Not covered

* No fault-injection tests for I/O errors (failed fsync, ENOSPC); the WAL rollback path is reviewed but untested.
* No fuzzing of the WAL/snapshot decoders beyond the exhaustive truncation test and the reviewer's targeted cases.
* No multi-hour soak or load test.
