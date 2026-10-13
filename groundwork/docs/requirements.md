# Requirements matrix — Project 10 (Production RAG + Evaluation Engine)

| Requirement | Status | Evidence |
|---|---|---|
| Document ingestion | VERIFIED | `ingest.py`; `test_core.py` parsing tests; API upload tests |
| Structured parsing (sections, pages) | VERIFIED | Markdown/HTML sections, PDF pages; `test_a4`, `test_a5`, `test_pdf_page_numbers_are_preserved` |
| Chunking strategies | VERIFIED | structure / sentence / fixed; property tests; ablation in docs/benchmarks.md |
| Embedding pipelines | PARTIALLY IMPLEMENTED | LSA + hashing embedders, refit with atomic re-embed (A1/A2 tests); **no neural model** (huggingface.co blocked) |
| BM25 | VERIFIED | from scratch; formula and delete-equivalence tests |
| Dense retrieval | VERIFIED (LSA/hashing only) | in-memory exact + pgvector HNSW benchmark |
| Hybrid retrieval | VERIFIED | RRF + weighted fusion; measured on test split |
| Reciprocal rank fusion | VERIFIED | `test_rrf_and_weighted_fusion`; benchmark |
| Reranking | VERIFIED (feature reranker) | tuned on dev, measured on test; **no cross-encoder** |
| Query rewriting | VERIFIED | RM3; benchmark |
| Citation grounding | VERIFIED | per-sentence citations + validation; D1/D2 regressions |
| Page-level references | VERIFIED | page-bounded chunks; API test returns `pages: [2, 2]` for a PDF |
| Evaluation datasets | VERIFIED (one dataset) | pystdlib known-item, 634 queries, dev/test split |
| Retrieval regression tests | VERIFIED | `tests/test_regression.py` vs `benchmarks/baseline.json` |
| Groundedness evaluation | VERIFIED (lexical proxy) | `check_grounding`; NLI not implemented |
| Citation correctness | VERIFIED | citation precision + relevant-section citation rate |
| Latency / cost tracking | VERIFIED | per-stage timings, query_log, `/v1/usage`; cost computed from provider token usage (mock-tested) |
| Provider abstraction | IMPLEMENTED BUT UNVERIFIED (real LLM) | extractive provider verified; OpenAI-compatible provider tested only with a mock transport |
| Prompt-injection testing | VERIFIED | unit tests + `injection_bench.py` (known 200/200 excluded; paraphrased 0/200) |
| Recall@K, MRR, NDCG | VERIFIED | `metrics.py` tests; benchmark with CIs |
| Citation precision/recall | VERIFIED | benchmark (recall measured as relevant-section citation rate, single relevant unit) |
| Answer relevance | NOT IMPLEMENTED (as a separate metric) | only proxied by relevant-section citation rate |
| Hallucination rate | VERIFIED for extractive (0 by construction); NOT MEASURED for LLMs | — |
| Separate retrieval vs generation evaluation | VERIFIED | separate sections of the benchmark |
| PostgreSQL + pgvector | VERIFIED | integration tests on PostgreSQL 16.15 + pgvector 0.6.0 |
| Redis | VERIFIED | cache + rate limit tests, outage degradation test |
| FastAPI service | VERIFIED | `test_api.py`, `scripts/demo.sh` log |
| CI | VERIFIED | `../.github/workflows/groundwork.yml` green on GitHub Actions (run 38478453668) |
| Container deployment | IMPLEMENTED BUT UNVERIFIED | Dockerfile/compose not built (Docker Hub blocked) |
| LICENSE | BLOCKED | owner decision |
