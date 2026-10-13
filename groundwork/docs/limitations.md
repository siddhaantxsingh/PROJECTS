# Known limitations

1. **No pretrained neural embedder or reranker.** Dense retrieval uses LSA or feature hashing because no model could be downloaded in the build environment. Hybrid-retrieval conclusions here apply to these embedders only.
2. **No LLM generation was evaluated.** The OpenAI-compatible provider is implemented and tested only against a mock transport. Groundedness and citation precision of the default extractive provider are 1.0 by construction; its useful metrics are abstention rate and whether it cites the relevant section.
3. **Grounding check is lexical.** `support_score` accepts a sentence whose content words occur in the cited source; it can accept a sentence that recombines source words into a false statement, and it rejects valid paraphrases. It is not an NLI model.
4. **Prompt-injection detection is pattern-based.** Paraphrased injections were not detected at all in the benchmark (0/200); see `docs/security.md`.
5. **One evaluation dataset.** pystdlib is a known-item benchmark over Python docstrings (one relevant section per query); it does not measure multi-hop questions or answer completeness. Chunking strategies that ignore sections are scored against section labels using the chunk's first section, which disadvantages `fixed`/`sentence` chunking beyond their intrinsic quality.
6. **BM25 tuning hit the grid edge** (k1 = 0.6, b = 0.3 are the smallest values tried); a wider grid may do better.
7. **Single process.** The query index is in memory per process and guarded by one lock; multiple replicas would each hold a copy and would need a shared invalidation channel (Redis generation counters are shared, the in-memory index is not).
8. **pgvector filtering.** pgvector 0.6 filters by tenant after the HNSW scan; `ann_search` raises `ef_search` to ≥4k but a very selective filter can still return fewer than k rows. The production query path uses the exact in-memory matrix.
9. **Not deployed.** Docker image and compose file were not built here (Docker Hub unreachable); CI has not run on GitHub.
10. **No real users.** No user feedback or adoption data exists.
