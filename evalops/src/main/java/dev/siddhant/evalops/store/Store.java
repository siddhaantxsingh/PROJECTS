package dev.siddhant.evalops.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** All database access. */
@Repository
public class Store {
    public static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final JdbcClient db;

    public Store(JdbcClient db) {
        this.db = db;
    }

    public record Item(int idx, String input, String reference, String meta) {
    }

    public record Dataset(long id, String name, int version, String sha256, int items, Instant created) {
    }

    public record Target(long id, String name, String url, String model, String params) {
    }

    public record Run(long id, long datasetId, long targetId, String targetSnapshot, List<String> metrics, int concurrency, boolean useCache,
                      String status, Instant created, Instant finished, String error) {
    }

    public record Result(int idx, String output, Double latencyMs, String error, Map<String, Double> scores, boolean cached, int attempts) {
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(Object o) {
        try {
            return JSON.writeValueAsString(o instanceof JsonNode n ? JSON.treeToValue(n, Object.class) : o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------ datasets

    /**
     * Store a dataset. Content identical to the latest version of the same
     * name returns that version; anything else becomes version n + 1.
     * Versions are immutable.
     */
    @Transactional
    public Dataset addDataset(String name, List<Item> items) {
        String sha = sha256(canonical(items));
        Optional<Dataset> latest = latest(name);
        if (latest.isPresent() && latest.get().sha256().equals(sha)) return latest.get();
        int version = latest.map(d -> d.version() + 1).orElse(1);
        var kh = new GeneratedKeyHolder();
        db.sql("insert into dataset(name, version, sha256, items, created) values (?, ?, ?, ?, ?)")
                .params(name, version, sha, items.size(), Timestamp.from(Instant.now())).update(kh, "id");
        long id = kh.getKey().longValue();
        for (Item it : items) {
            db.sql("insert into dataset_item(dataset_id, idx, input, reference, meta) values (?, ?, ?, ?, ?)")
                    .params(id, it.idx(), it.input(), it.reference(), it.meta()).update();
        }
        return dataset(id).orElseThrow();
    }

    private static Dataset ds(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Dataset(r.getLong("id"), r.getString("name"), r.getInt("version"), r.getString("sha256"), r.getInt("items"),
                r.getTimestamp("created").toInstant());
    }

    public Optional<Dataset> latest(String name) {
        return db.sql("select * from dataset where name = ? order by version desc limit 1").param(name).query((r, i) -> ds(r)).optional();
    }

    public Optional<Dataset> dataset(long id) {
        return db.sql("select * from dataset where id = ?").param(id).query((r, i) -> ds(r)).optional();
    }

    public Optional<Dataset> dataset(String name, int version) {
        return db.sql("select * from dataset where name = ? and version = ?").params(name, version).query((r, i) -> ds(r)).optional();
    }

    public List<Dataset> datasets() {
        return db.sql("select * from dataset order by name, version").query((r, i) -> ds(r)).list();
    }

    public List<Item> items(long datasetId) {
        return db.sql("select * from dataset_item where dataset_id = ? order by idx").param(datasetId)
                .query((r, i) -> new Item(r.getInt("idx"), r.getString("input"), r.getString("reference"), r.getString("meta"))).list();
    }

    // ------------------------------------------------------------ targets

    public Target putTarget(String name, String url, String model, String params) {
        int n = db.sql("update target set url = ?, model = ?, params = ? where name = ?").params(url, model, params, name).update();
        if (n == 0) {
            db.sql("insert into target(name, url, model, params, created) values (?, ?, ?, ?, ?)")
                    .params(name, url, model, params, Timestamp.from(Instant.now())).update();
        }
        return target(name).orElseThrow();
    }

    public Optional<Target> target(String name) {
        return db.sql("select * from target where name = ?").param(name)
                .query((r, i) -> new Target(r.getLong("id"), r.getString("name"), r.getString("url"), r.getString("model"), r.getString("params")))
                .optional();
    }

    public List<Target> targets() {
        return db.sql("select * from target order by name")
                .query((r, i) -> new Target(r.getLong("id"), r.getString("name"), r.getString("url"), r.getString("model"), r.getString("params"))).list();
    }

    // ------------------------------------------------------------ runs

    public long createRun(long datasetId, long targetId, String snapshot, List<String> metrics, int concurrency, boolean useCache) {
        var kh = new GeneratedKeyHolder();
        db.sql("insert into run(dataset_id, target_id, target_snapshot, metrics, concurrency, use_cache, status, created) values (?, ?, ?, ?, ?, ?, 'queued', ?)")
                .params(datasetId, targetId, snapshot, String.join(",", metrics), concurrency, useCache, Timestamp.from(Instant.now())).update(kh, "id");
        return kh.getKey().longValue();
    }

    private static Run run(java.sql.ResultSet r) throws java.sql.SQLException {
        Timestamp f = r.getTimestamp("finished");
        return new Run(r.getLong("id"), r.getLong("dataset_id"), r.getLong("target_id"), r.getString("target_snapshot"),
                List.of(r.getString("metrics").split(",")), r.getInt("concurrency"), r.getBoolean("use_cache"), r.getString("status"),
                r.getTimestamp("created").toInstant(), f == null ? null : f.toInstant(), r.getString("error"));
    }

    public Optional<Run> run(long id) {
        return db.sql("select * from run where id = ?").param(id).query((r, i) -> run(r)).optional();
    }

    public List<Run> runs() {
        return db.sql("select * from run order by id").query((r, i) -> run(r)).list();
    }

    public List<Run> runsWithStatus(String status) {
        return db.sql("select * from run where status = ? order by id").param(status).query((r, i) -> run(r)).list();
    }

    public void setStatus(long id, String status, String error) {
        boolean end = status.equals("done") || status.equals("failed");
        db.sql("update run set status = ?, error = ?, finished = ? where id = ?")
                .params(status, error, end ? Timestamp.from(Instant.now()) : null, id).update();
    }

    public void putResult(long runId, Result r) {
        db.sql("merge into result(run_id, idx, output, latency_ms, error, scores, cached, attempts) key(run_id, idx) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(runId, r.idx(), r.output(), r.latencyMs(), r.error(), canonical(r.scores()), r.cached(), r.attempts()).update();
    }

    @SuppressWarnings("unchecked")
    public List<Result> results(long runId) {
        return db.sql("select * from result where run_id = ? order by idx").param(runId).query((r, i) -> {
            Map<String, Double> scores;
            try {
                scores = JSON.readValue(r.getString("scores"), Map.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            double lat = r.getDouble("latency_ms");
            return new Result(r.getInt("idx"), r.getString("output"), r.wasNull() ? null : lat, r.getString("error"), scores, r.getBoolean("cached"),
                    r.getInt("attempts"));
        }).list();
    }

    // ------------------------------------------------------------ response cache

    public Optional<String> cached(String key) {
        return db.sql("select output from response_cache where cache_key = ?").param(key).query(String.class).optional();
    }

    public void cache(String key, String output) {
        db.sql("merge into response_cache(cache_key, output, created) key(cache_key) values (?, ?, ?)")
                .params(key, output, Timestamp.from(Instant.now())).update();
    }
}
