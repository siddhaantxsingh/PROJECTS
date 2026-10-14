# Limitations

* **No security layer.** The service has no authentication or multi-tenancy: deploy it behind an authenticating proxy.
* **One target protocol.** Only OpenAI-style `/v1/completions` is supported: one prompt in, `choices[0].text` out. There is no chat format, tool calls or multi-turn conversations.
* **No LLM-as-judge or execution-based metrics.** There is no pass@k, because running model-generated code needs a sandbox. Metrics are reference-based string metrics.
* **Embedded H2 and a single instance.** There is no queue shared between instances. In-progress runs resume after a restart, but only on the same instance.
* **A failed item scores 0.** This is deliberate: errors count against a run. It does mean that infrastructure failures and model failures are mixed in the means; error counts are reported separately.
* **The gate's significance is per metric.** There is no correction for multiple comparisons across rules.
