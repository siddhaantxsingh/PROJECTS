# Design

EvalOps is a Spring Boot service with embedded H2 storage, accessed through Spring JDBC. It evaluates model endpoints that speak the OpenAI `/v1/completions` API, compares runs statistically, and gates CI on regressions.

## Concepts

* **Dataset.** A named, *immutable*, versioned list of items (`input`, optional `reference`, optional `meta`).
  * Uploading content identical to the latest version returns that version; any change creates version n + 1.
  * Each version stores the SHA-256 of its canonical JSON.
* **Target.** An endpoint URL, a model name and request parameters (`max_tokens`, `temperature`, `stop`, …).
  * A run stores a *snapshot* of its target's configuration. Editing a target later never changes what an old run means.
* **Run.** A dataset version × a target snapshot × a list of metrics.
  * Every item is sent and its output scored; one row per item holds the output, latency, attempt count, error and scores.
* **Metrics** (`metrics/Metrics.java`; each returns a score in [0, 1]):

  | Metric | Definition |
  |---|---|
  | `exact_match` | after whitespace normalisation |
  | `contains` | the output contains the reference |
  | `regex_match` | the reference is a regular expression |
  | `first_line_exact` | the first lines match (for code completion) |
  | `edit_similarity` | 1 − Levenshtein / max length |
  | `rouge_l_f1` | ROUGE-L F1 over whitespace tokens |
  | `bleu` | sentence BLEU-4, smoothed |
  | `json_valid` | the output parses as JSON |

## Execution

* **Concurrency.** Each run is bounded by a semaphore and runs on virtual threads.
* **Retries.** Transient failures (connection errors, 429, 5xx) are retried up to `max-attempts` times with exponential backoff, starting at 200 ms. Other errors fail the item.
  * A failed item scores 0 on every metric, so failures count against a run instead of disappearing.
  * A run in which every item failed is marked `failed`.
* **Response cache.** Responses are cached under SHA-256(target snapshot, input).
  * Re-running an identical configuration sends nothing and reproduces the same outputs.
  * `"cache": false` forces new requests, for example for sampling targets.
* **Resume.** Results are written per item. On start-up, runs that were `queued` or `running` resume with only their unfinished items.

## Statistics (`stats/Stats.java`)

Resampling is seeded from the run ids, so a report is reproducible.

* **Run summary.** For each metric, the mean and a 95 % percentile bootstrap interval. Also the latency p50 and p95, error and cache counts.
* **Comparison.** Two runs on the **same dataset version** are paired item by item. Runs on different versions are refused.
  * For each metric: the mean difference, a 95 % bootstrap interval of it, and a two-sided **sign-flip permutation** p-value. That p-value is exact under the null that each item's difference is symmetric about zero.
  * Pairing removes the variance from item difficulty, which both runs share.
  * The comparison also lists the items that regressed most on the first metric, with both outputs.
* **Gates.** Each rule may set two independent conditions:
  * `maxDrop`: fail if the metric drops by more than this **and** the drop is significant (p < `alpha`, default 0.05);
  * `minValue`: fail if the candidate's mean is below this.

  `scripts/gate.sh` turns the result into an exit code for CI.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/datasets` | `{name, items: [{input, reference?, meta?}]}` → version |
| GET | `/datasets`, `/datasets/{name}/{version}` | list, fetch with items |
| PUT | `/targets/{name}` | `{url, model?, params?}` |
| POST | `/runs` | `{dataset, version?, target, metrics: [...], concurrency?, cache?}` → 202 `{run}` |
| GET | `/runs/{id}`, `/runs/{id}/results` | summary with intervals; per-item rows |
| GET | `/compare?baseline=&candidate=` | paired comparison and regressions |
| POST | `/gates/check` | `{baseline, candidate, rules: [{metric, maxDrop?, alpha?, minValue?}]}` → `{pass, checks}` |
| GET | `/actuator/prometheus` | item counters by outcome (ok, cached, error) |
