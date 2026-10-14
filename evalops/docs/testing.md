# Testing

`mvn verify` runs 8 tests in about 20 s.

## `MetricsStatsTest`

* **Metrics on hand-computed cases.**
  * Edit similarity of kitten/sitting is 1 − 3/7.
  * The ROUGE-L of a sentence with one token deleted is checked against its hand-computed value.
  * Identical sentences get BLEU 1; disjoint ones get 0.
  * An invalid regular expression scores 0 instead of throwing.
* **Bootstrap coverage.** On 300 simulated Bernoulli(0.6) samples of size 200, the 95 % interval contains the true mean 91–98 % of the time (the assertion's bounds).
* **Paired test calibration.** Each simulated item has a difficulty that affects both runs. Over 300 trials at n = 300:
  * between two runs of equal quality, the false-positive rate at α = 0.05 is within 2–9 %;
  * a +0.15 effect is detected in more than 90 % of trials.

  The analytic power is about 0.98. A first version used n = 150, where the power is only about 0.82; the test's expectation was wrong, not the code, and the test was corrected.
* **Reproducibility.** The same inputs and seed give the same report.

## `EvalOpsIntegrationTest`

These run the real service with an in-memory H2 database, against a fake OpenAI-compatible endpoint (a JDK HTTP server). The fake's answer accuracy is a request parameter, and which items it answers correctly is a hash of the prompt, so runs are deterministic. It also injects one 503 per "FLAKY" item and a 400 for every "BROKEN" item.

| Test | What it proves |
|---|---|
| `datasetsAreVersionedByContent` | Identical uploads reuse a version; any change creates the next one; invalid names and empty datasets are rejected. |
| `runsRetryCacheAndReport` | **Retries.** Every flaky item takes exactly 2 attempts (120 requests for 60 items), and the run scores 1.0. **Caching.** An identical second run sends **zero** requests (60 cached). `cache: false` sends 60. **Permanent errors.** A 400 is not retried: the items are recorded as errors and the run is `failed`. **Validation.** Unknown metrics, targets and datasets are rejected. |
| `comparisonsAndGatesSeparateRealRegressionsFromNoise` | 300 items; targets of accuracy 0.85, 0.85 (a different configuration) and 0.65. **Real regression.** The 0.85 → 0.65 drop has a negative interval and p < 0.01, its regressions list holds real worse items, and the gate fails. **No regression.** Reversing the direction passes the gate. **Equal quality** passes too: no false alarm. **Floors.** A `minValue` rule fails with its own reason. **Pairing.** Runs on different dataset versions are refused. |
| `interruptedRunsResumeWithoutRedoingFinishedItems` | A run "interrupted" after 7 of 20 items resumes on start-up and sends exactly 13 requests. |

The integration tests found a design flaw. A rule with only `minValue` also applied a drop check, because `maxDrop` silently defaulted to 0. Each condition now applies only when given, and a rule with neither is rejected.
