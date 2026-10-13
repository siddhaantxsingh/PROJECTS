# Groundwork

Groundwork is a retrieval and grounded-answer engine. Its main focus is measuring retrieval quality and citation grounding, not just serving answers. It ingests Markdown, HTML, text and PDF (with page numbers). It retrieves with BM25 (written from scratch), dense vectors (pgvector) or a hybrid of the two. It returns answers whose every sentence cites a source chunk, by document, page and section, and it checks each citation. It also ships with a reproducible evaluation harness, a regression gate, and an adversarial review of its own defects.

**Status:**
- **Done:** the core engine and service, verified locally. 72 tests pass against real PostgreSQL + pgvector and Redis.
- **Not done:**
  - not deployed;
  - no neural embedding model or LLM was available in the build environment (see [Limitations](#known-limitations)).

## What it does

```
upload ──► parse (page/section aware) ──► chunk (never crosses a page) ──► embed ──► PostgreSQL + pgvector (one transaction)
query  ──► [RM3 expansion] ──► BM25 ∥ dense ──► RRF / weighted fusion ──► [feature reranker]
       ──► injection scan + source fencing ──► provider (extractive | OpenAI-compatible) ──► grounding check ──► answer + citations
```

- **Multi-tenant HTTP API (FastAPI).** API keys map to tenants. Endpoints:
  - upload, list and delete documents (deleting a document removes its data from the database);
  - query;
  - usage accounting (latency, tokens, cost);
  - health, and Prometheus metrics.
- **Redis.** Used for a query cache, invalidated per tenant on every change, and for rate limits. Both degrade safely when Redis is down.
- **Evaluation.**
  - Retrieval metrics: Recall@K, MRR, NDCG, with bootstrap confidence intervals and paired tests.
  - Generation metrics: groundedness, citation precision, abstention rate, and whether the answer cites the relevant section.
  - Prompt-injection robustness.
  - Latency percentiles.
  - Retrieval and generation are evaluated separately. No LLM judge is used.

## Core algorithms (implemented here, not wrapped)

| Area | Implementation |
|---|---|
| Lexical retrieval | Inverted index with Okapi BM25 (Lucene IDF). Supports incremental add and delete; a property test checks that deleting a document gives the same scores as never adding it. |
| Dense retrieval | LSA (TF-IDF + truncated SVD, fitted per tenant) or signed feature hashing, behind an `Embedder` protocol. pgvector HNSW is benchmarked against exact search. |
| Fusion | Reciprocal rank fusion, and min-max weighted fusion. Metadata filters are applied before top-k. |
| Query rewriting | RM3 pseudo-relevance feedback (relevance model interpolated with the original query). |
| Reranking | Linear model over first-stage score, query-term coverage, minimal covering window (proximity), heading match and dense similarity. Weights are tuned on the dev split. |
| Grounding | Each answer sentence carries citations. Each citation is checked for lexical support of its sentence; uncited, unsupported and wrongly cited sentences are reported. |
| Safety | Injection scanner (after NFKC normalisation and removal of zero-width characters). Untrusted sources are fenced so delimiter look-alikes can't escape. Output is sanitised so links and images can't exfiltrate data. |

## Results (pystdlib test split, 316 queries — full tables in [docs/benchmarks.md](docs/benchmarks.md))

| Configuration | NDCG@10 [95 % CI] | MRR@10 | Recall@10 |
|---|---|---|---|
| BM25, default parameters | 0.470 [0.429, 0.512] | 0.404 | 0.677 |
| BM25, tuned on dev | 0.528 [0.483, 0.571] | 0.472 | 0.703 |
| BM25 + RM3 | 0.549 [0.507, 0.590] | 0.484 | **0.753** |
| BM25 + reranker | **0.550** [0.506, 0.592] | **0.494** | 0.725 |
| Dense LSA-384 | 0.351 [0.311, 0.390] | 0.283 | 0.570 |
| Hybrid (RRF) | 0.464 [0.422, 0.504] | 0.389 | 0.703 |

What the results show:

- On this dataset, tuned BM25 with RM3 or the reranker is best. The confidence intervals of the top configurations overlap.
- The offline dense retrievers are weak, and RRF with them does not help. That conclusion does not transfer to neural embedders.
- The extractive answer provider (over BM25 + reranker):
  - abstains on 6.0 % of test queries;
  - cites the labelled relevant section in 42 % of the answers it gives;
  - takes 5.8 ms end to end at the median.
- pgvector HNSW reaches recall@10 of at least 0.993 against exact search at `ef_search` ≥ 40.
- Prompt injection:
  - known-style injections were all kept out of the context (200/200);
  - **paraphrased injections were not detected at all (0/200)** and reached 6 % of extractive answers;
  - the detector is defence in depth, not a security boundary.

The dataset was built automatically from CPython standard-library docstrings ([how](docs/benchmarks.md#dataset-pystdlib-known-item-retrieval)). It is checked in, so results do not depend on the local Python version.

## Build, test, run

```bash
pip install -e '.[dev]'                      # Python ≥ 3.11
# PostgreSQL with pgvector on 127.0.0.1:5432 (user postgres), Redis on 6379
pytest                                       # 72 tests: unit, property, integration, fault injection, quality gate
./scripts/demo.sh                            # real server: upload, grounded Q&A with page refs, abstention
python -m groundwork serve --port 8080       # see docs/deployment.md for configuration
python benchmarks/retrieval_bench.py OUT --pg postgresql://postgres@127.0.0.1:5432/postgres
```

## Engineering trade-offs

- **PostgreSQL is the source of truth; the query index is in memory.** This makes BM25, RM3 and the reranker explicit and fast. The cost is start-up time, memory, and one process per index ([ADR 0002](docs/decisions/0002-in-memory-query-index.md)).
- **Offline embedders, labelled honestly** ([ADR 0001](docs/decisions/0001-dense-retrieval-without-pretrained-models.md)).
- **Deterministic evaluation; model judges never count as ground truth** ([ADR 0003](docs/decisions/0003-deterministic-evaluation.md)).
- **Chunks never cross a page**, so a citation always names exactly one page. The cost is a little extra fragmentation.
- **A lexical grounding check** instead of an NLI model: cheap and deterministic, but weaker ([limitations](docs/limitations.md)).

## Security assumptions

API keys are secrets that clients handle correctly. A tenant boundary corresponds to one API key. Retrieved documents are untrusted. The service runs behind TLS termination. See the threat model and measured limits in [docs/security.md](docs/security.md).

## Quality evidence

- [docs/testing.md](docs/testing.md): test inventory. The latest test log is in `test-logs/`.
- [docs/audit.md](docs/audit.md): 13 defects found by the benchmark, by an independent adversarial review, and by a downstream browser test (2 high, 8 medium or low-medium, 3 low). Each is fixed and has a regression test that fails on the old code.
- `tests/test_regression.py`: the retrieval-quality gate against `benchmarks/baseline.json`.

## Known limitations

These are summarised here; the full list is in [docs/limitations.md](docs/limitations.md).

- No neural embedder, reranker or LLM was evaluated. The OpenAI-compatible provider is tested only against a mock HTTP transport.
- Groundedness is a lexical proxy.
- Injection detection is pattern-based, and paraphrased injections get through.
- One known-item dataset; no multi-hop evaluation.
- Single process.
- Docker and CI files exist but were not run: Docker Hub and GitHub are unreachable from the build environment.
- No real users.

## Roadmap

1. Plug in a neural embedder and a cross-encoder reranker (the interfaces exist), then re-run the benchmark.
2. Evaluate a real LLM through the OpenAI-compatible provider, with grounding and citation checks, plus an NLI-based support model.
3. Add a second dataset with multi-hop and unanswerable questions.
4. Shared index across replicas, and pgvector on the query path.
5. Add a model-based injection classifier alongside the patterns, evaluated on a held-out paraphrase set.

License: not yet chosen by the owner.
