package dev.siddhant.mcpfabric.policy;

import dev.siddhant.mcpfabric.FabricProperties.ClientConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Client authentication (bearer token, compared by SHA-256 digest in
 * constant time), tool authorization (allow/deny glob lists over namespaced
 * tool names; deny wins) and per-client token-bucket rate limits.
 */
public class Policy {
    /**
     * An authenticated caller. `id` keys the rate-limit bucket; `subject` is the
     * end user (or the static client id); `servers` restricts the upstreams the
     * token's scopes grant (null = no restriction, static mode).
     */
    public record Client(String id, String subject, List<Pattern> allow, List<Pattern> deny, double perSecond, double burst,
                         java.util.Set<String> servers) {
        public Client(String id, List<Pattern> allow, List<Pattern> deny, double perSecond, double burst) {
            this(id, id, allow, deny, perSecond, burst, null);
        }

        /** Identity a session is bound to. */
        public String principal() {
            return id + "|" + subject;
        }
    }

    private final Map<String, Client> tenants = new ConcurrentHashMap<>();

    private final Map<String, byte[]> digests = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public Policy(List<ClientConfig> cfg, Clock clock) {
        this(cfg, List.of(), clock);
    }

    public Policy(List<ClientConfig> cfg, List<dev.siddhant.mcpfabric.FabricProperties.TenantConfig> tenantCfg, Clock clock) {
        this.clock = clock;
        for (var t : tenantCfg) {
            var allow = (t.allow() == null ? List.of("*") : t.allow()).stream().map(Policy::glob).toList();
            var deny = (t.deny() == null ? List.<String>of() : t.deny()).stream().map(Policy::glob).toList();
            int rpm = t.ratePerMinute() == null ? 600 : t.ratePerMinute();
            int burst = t.burst() == null ? Math.max(1, rpm / 10) : t.burst();
            tenants.put(t.name(), new Client(t.name(), t.name(), allow, deny, rpm / 60.0, burst, null));
        }
        for (ClientConfig c : cfg) {
            var allow = (c.allow() == null ? List.of("*") : c.allow()).stream().map(Policy::glob).toList();
            var deny = (c.deny() == null ? List.<String>of() : c.deny()).stream().map(Policy::glob).toList();
            int rpm = c.ratePerMinute() == null ? 600 : c.ratePerMinute();
            int burst = c.burst() == null ? Math.max(1, rpm / 10) : c.burst();
            clients.put(c.id(), new Client(c.id(), allow, deny, rpm / 60.0, burst));
            digests.put(c.id(), HexFormat.of().parseHex(c.keySha256()));
        }
    }

    public static Pattern glob(String g) {
        StringBuilder r = new StringBuilder();
        for (char ch : g.toCharArray()) r.append(ch == '*' ? ".*" : ch == '?' ? "." : Pattern.quote(String.valueOf(ch)));
        return Pattern.compile(r.toString());
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Client> authenticate(String bearer) {
        if (bearer == null) return Optional.empty();
        byte[] d = HexFormat.of().parseHex(sha256Hex(bearer));
        Client found = null;
        for (var e : digests.entrySet()) {
            // examine every entry so timing does not reveal which client matched
            if (MessageDigest.isEqual(e.getValue(), d)) found = clients.get(e.getKey());
        }
        return Optional.ofNullable(found);
    }

    /**
     * The client for a validated access token: the tenant's policy, narrowed to
     * the upstreams the token's scopes grant. Empty if the tenant is unknown.
     */
    public Optional<Client> forToken(String tenant, String clientId, String subject, java.util.Set<String> servers) {
        Client t = tenant == null ? null : tenants.get(tenant);
        if (t == null) return Optional.empty();
        return Optional.of(new Client(tenant + "/" + clientId, subject, t.allow(), t.deny(), t.perSecond(), t.burst(), servers));
    }

    public boolean allowed(Client c, String tool) {
        if (c.deny().stream().anyMatch(p -> p.matcher(tool).matches())) return false;
        if (c.servers() != null && !c.servers().contains("*")) {
            int i = tool.indexOf("__");
            if (i <= 0 || !c.servers().contains(tool.substring(0, i))) return false;
        }
        return c.allow().stream().anyMatch(p -> p.matcher(tool).matches());
    }

    /** Take one token from the client's bucket. */
    public boolean admit(Client c) {
        return buckets.computeIfAbsent(c.id(), k -> new Bucket(c.burst(), clock.millis())).take(c.perSecond(), c.burst(), clock.millis());
    }

    static final class Bucket {
        private double tokens;
        private long last;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.last = now;
        }

        synchronized boolean take(double rate, double burst, long now) {
            tokens = Math.min(burst, tokens + (now - last) / 1000.0 * rate);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
