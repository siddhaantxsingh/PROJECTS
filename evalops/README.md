# EvalOps (project 19)

An evaluation service for LLM endpoints, built with Java 21, Spring Boot 3 and H2. It:

* keeps **versioned, immutable datasets**;
* runs them against any OpenAI-style `/v1/completions` endpoint, with bounded concurrency, retries and a response cache, and resumes after a crash;
* scores outputs with standard metrics;
* compares runs with **paired bootstrap statistics**;
* turns those comparisons into **CI gates**.

**Status: done.** All 8 tests pass, including statistical calibration tests. The demo evaluates this repository's own `llm-inference-engine` ([docs/demo.md](docs/demo.md)).

## Demo result

300 next-line completions of held-out Python code, scored against the f32 model:

| candidate | first-line exact Δ | edit similarity Δ | gate |
|---|---|---|---|
| int8 weights | +0.007 (p = 0.49) | −0.002 (p = 0.66) | **pass** |
| 1-layer draft model | −0.137 (p < 0.001) | −0.085 (p < 0.001) | **fail** |

## Quick start

```sh
mvn -B package
java -jar target/evalops-0.1.0.jar                                   # :8095, H2 file in ./data
curl -X POST :8095/datasets -H 'Content-Type: application/json' -d '{"name":"qa","items":[{"input":"2+2=","reference":"4"}]}'
curl -X PUT  :8095/targets/my-model -H 'Content-Type: application/json' -d '{"url":"http://127.0.0.1:8080/v1/completions","params":{"max_tokens":8}}'
curl -X POST :8095/runs -H 'Content-Type: application/json' -d '{"dataset":"qa","target":"my-model","metrics":["exact_match"]}'
curl :8095/runs/1                                                    # means with 95 % intervals
scripts/gate.sh http://127.0.0.1:8095 1 2 '[{"metric":"exact_match","maxDrop":0.01}]'   # exit 0 / 1 / 2
demo/run_demo.sh demo/results                                        # the llm-inference-engine evaluation
```

## Documentation

* [Design](docs/design.md): data model, execution, statistics and API.
* [Testing](docs/testing.md): metrics on known values, and the bootstrap's coverage, false-positive rate and power.
* [Demo](docs/demo.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
