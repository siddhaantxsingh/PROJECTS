package dev.siddhant.evalops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.metrics.Metrics;
import dev.siddhant.evalops.runner.Reports;
import dev.siddhant.evalops.runner.RunService;
import dev.siddhant.evalops.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Api {
    private final Store store;
    private final RunService runs;
    private final Reports reports;

    public Api(Store store, RunService runs, Reports reports) {
        this.store = store;
        this.runs = runs;
        this.reports = reports;
    }

    @ExceptionHandler({Reports.BadRequest.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    static ResponseEntity<?> notFound(String what) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", what + " not found"));
    }

    // ------------------------------------------------------------ datasets

    /** {"name": "...", "items": [{"input": "...", "reference": "...", "meta": {...}}]} */
    @PostMapping("/datasets")
    public ResponseEntity<?> addDataset(@RequestBody JsonNode body) {
        String name = body.path("name").asText("");
        if (!name.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("name must match [A-Za-z0-9_.-]{1,100}");
        if (!body.path("items").isArray() || body.path("items").isEmpty()) throw new IllegalArgumentException("items must be a non-empty array");
        List<Store.Item> items = new ArrayList<>();
        int i = 0;
        for (JsonNode it : body.get("items")) {
            if (!it.path("input").isTextual()) throw new IllegalArgumentException("item " + i + ": input must be a string");
            items.add(new Store.Item(i++, it.get("input").asText(), it.path("reference").isMissingNode() ? null : it.path("reference").asText(),
                    it.has("meta") ? it.get("meta").toString() : null));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(store.addDataset(name, items));
    }

    @GetMapping("/datasets")
    public List<Store.Dataset> datasets() {
        return store.datasets();
    }

    @GetMapping("/datasets/{name}/{version}")
    public ResponseEntity<?> dataset(@PathVariable String name, @PathVariable int version) {
        return store.dataset(name, version).<ResponseEntity<?>>map(d -> ResponseEntity.ok(Map.of("dataset", d, "items", store.items(d.id()))))
                .orElseGet(() -> notFound("dataset"));
    }

    // ------------------------------------------------------------ targets

    /** {"url": ".../v1/completions", "model": "...", "params": {"max_tokens": 64, "temperature": 0, "stop": ["\n"]}} */
    @PutMapping("/targets/{name}")
    public Store.Target putTarget(@PathVariable String name, @RequestBody JsonNode body) {
        String url = body.path("url").asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("url must be http(s)");
        JsonNode params = body.path("params").isObject() ? body.get("params") : Store.JSON.createObjectNode();
        return store.putTarget(name, url, body.hasNonNull("model") ? body.get("model").asText() : null, Store.canonical(params));
    }

    @GetMapping("/targets")
    public List<Store.Target> targets() {
        return store.targets();
    }

    // ------------------------------------------------------------ runs

    /** {"dataset": "name", "version": 2 (default latest), "target": "name", "metrics": [...], "concurrency": 8, "cache": true} */
    @PostMapping("/runs")
    public ResponseEntity<?> createRun(@RequestBody JsonNode body) {
        String dsName = body.path("dataset").asText();
        var ds = body.has("version") ? store.dataset(dsName, body.get("version").asInt()) : store.latest(dsName);
        if (ds.isEmpty()) return notFound("dataset");
        var target = store.target(body.path("target").asText());
        if (target.isEmpty()) return notFound("target");
        List<String> metrics = new ArrayList<>();
        body.path("metrics").forEach(m -> metrics.add(m.asText()));
        if (metrics.isEmpty()) throw new IllegalArgumentException("metrics required; available: " + Metrics.names());
        for (String m : metrics) if (!Metrics.ALL.containsKey(m)) throw new IllegalArgumentException("unknown metric " + m + "; available: " + Metrics.names());
        int conc = body.path("concurrency").asInt(4);
        if (conc < 1 || conc > 256) throw new IllegalArgumentException("concurrency must be 1..256");
        var t = target.get();
        ObjectNode snap = Store.JSON.createObjectNode().put("url", t.url()).put("model", t.model());
        try {
            snap.set("params", Store.JSON.readTree(t.params()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long id = store.createRun(ds.get().id(), t.id(), Store.canonical(snap), metrics, conc, body.path("cache").asBoolean(true));
        runs.start(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("run", id, "dataset_version", ds.get().version()));
    }

    @GetMapping("/runs")
    public List<Store.Run> runs() {
        return store.runs();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable long id) {
        return store.run(id).<ResponseEntity<?>>map(r -> ResponseEntity.ok(reports.summary(r))).orElseGet(() -> notFound("run"));
    }

    @GetMapping("/runs/{id}/results")
    public ResponseEntity<?> results(@PathVariable long id) {
        return store.run(id).<ResponseEntity<?>>map(r -> ResponseEntity.ok(store.results(id))).orElseGet(() -> notFound("run"));
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam long baseline, @RequestParam long candidate, @RequestParam(defaultValue = "10") int regressions) {
        var b = store.run(baseline);
        var c = store.run(candidate);
        if (b.isEmpty() || c.isEmpty()) return notFound("run");
        return ResponseEntity.ok(reports.compare(b.get(), c.get(), regressions));
    }

    /** {"baseline": 1, "candidate": 2, "rules": [{"metric": "exact_match", "maxDrop": 0.01, "alpha": 0.05, "minValue": 0.5}]} */
    @PostMapping("/gates/check")
    public ResponseEntity<?> gate(@RequestBody JsonNode body) {
        var b = store.run(body.path("baseline").asLong());
        var c = store.run(body.path("candidate").asLong());
        if (b.isEmpty() || c.isEmpty()) return notFound("run");
        List<Reports.Rule> rules = new ArrayList<>();
        for (JsonNode r : body.path("rules")) {
            rules.add(new Reports.Rule(r.path("metric").asText(), r.hasNonNull("maxDrop") ? r.get("maxDrop").asDouble() : null,
                    r.hasNonNull("alpha") ? r.get("alpha").asDouble() : null, r.hasNonNull("minValue") ? r.get("minValue").asDouble() : null));
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("rules required");
        ObjectNode res = reports.gate(b.get(), c.get(), rules);
        // 200 either way; CI reads "pass" (or uses scripts/gate.sh, which sets the exit code)
        return ResponseEntity.ok(res);
    }
}
