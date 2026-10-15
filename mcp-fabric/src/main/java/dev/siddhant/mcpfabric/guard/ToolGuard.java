package dev.siddhant.mcpfabric.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.policy.Policy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defends clients against hostile tool metadata from upstreams.
 *
 * Tool poisoning: descriptions and titles are scanned for instruction-like
 * text aimed at the model (hidden "IMPORTANT" blocks, "ignore previous
 * instructions", "do not tell the user", references to credentials such as
 * ~/.ssh) and for invisible Unicode (zero-width, bidi, tag characters), which
 * is always stripped. For untrusted upstreams a finding redacts the text (or
 * hides the tool); for trusted ones it is only reported.
 *
 * Rug pulls: each tool definition is hashed (canonical JSON) and pinned the
 * first time it is seen. If an upstream later changes a pinned definition,
 * the tool is quarantined (hidden, calls refused) and an audit record is
 * written until an admin accepts the new definition.
 *
 * This is a heuristic filter, not a proof of safety: it catches known
 * patterns and any change after pinning.
 */
public class ToolGuard {
    public enum State { OK, FLAGGED, REDACTED, BLOCKED, CHANGED }

    /** raw: the upstream's definition (renamed); shown: what clients see. */
    public record View(String name, String upstream, JsonNode raw, JsonNode shown, String hash, String pinned, State state, List<String> findings) {
        public boolean callable() {
            return state != State.BLOCKED && state != State.CHANGED;
        }

        public ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode().put("name", name).put("upstream", upstream).put("state", state.name())
                    .put("hash", hash).put("pinned", pinned);
            findings.forEach(o.putArray("findings")::add);
            return o;
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CANON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);
    public static final String REDACTED_TEXT = "[removed by mcp-fabric: suspicious instructions in tool metadata]";

    private static final Map<String, Pattern> RULES = Map.of(
            "hidden_tag", Pattern.compile("<\\s*/?\\s*(important|system|instructions?|secret|admin)\\b", Pattern.CASE_INSENSITIVE),
            "override_instructions", Pattern.compile("\\b(ignore|disregard|forget)\\s+(all\\s+|any\\s+)?(the\\s+|your\\s+)?(previous|prior|above|earlier|other|system)\\s+(instructions|prompts?|rules)", Pattern.CASE_INSENSITIVE),
            "conceal_from_user", Pattern.compile("\\b(do\\s+not|don't|never)\\s+(tell|inform|mention|reveal|show|alert|notify)\\b", Pattern.CASE_INSENSITIVE),
            "cross_tool_directive", Pattern.compile("\\b(before|after|instead\\s+of)\\s+(using|calling|invoking)\\s+(this|any|the|other)\\b", Pattern.CASE_INSENSITIVE),
            "credential_reference", Pattern.compile("(\\.ssh\\b|id_rsa|id_ed25519|\\.aws/credentials|\\.netrc|\\.npmrc|/etc/(passwd|shadow)|(^|[\\s/])\\.env\\b|private[ _-]key|mcp\\.json|api[ _-]?key|access[ _-]token)", Pattern.CASE_INSENSITIVE),
            "prompt_reference", Pattern.compile("\\b(system|developer)\\s+prompt\\b|\\b(conversation|chat)\\s+history\\b", Pattern.CASE_INSENSITIVE),
            "html_comment", Pattern.compile("<!--"));
    /** Zero-width, bidi controls, BOM, Unicode tag block and other format (Cf) characters. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\x{E0000}-\\x{E007F}]");
    private static final Set<String> TEXT_FIELDS = Set.of("description", "title");

    private final AuditLog audit;
    private final String untrustedAction;
    private final int maxDescription;
    private final Path pinsFile;
    private final Map<String, String> pins = new ConcurrentHashMap<>();
    private final Map<String, View> views = new ConcurrentHashMap<>();

    public ToolGuard(AuditLog audit, String untrustedAction, int maxDescription, String pinsFile) {
        if (!Set.of("redact", "block", "flag").contains(untrustedAction)) throw new IllegalArgumentException("untrusted-action: " + untrustedAction);
        this.audit = audit;
        this.untrustedAction = untrustedAction;
        this.maxDescription = maxDescription;
        this.pinsFile = pinsFile == null ? null : Path.of(pinsFile);
        if (this.pinsFile != null && Files.exists(this.pinsFile)) {
            try {
                JSON.readTree(this.pinsFile.toFile()).fields().forEachRemaining(e -> pins.put(e.getKey(), e.getValue().asText()));
            } catch (Exception e) {
                throw new IllegalStateException("cannot read tool pins " + pinsFile, e);
            }
        }
    }

    public static String hash(JsonNode tool) {
        try {
            return Policy.sha256Hex(CANON.writeValueAsString(CANON.treeToValue(tool, Object.class)));
        } catch (Exception e) {
            return Policy.sha256Hex(tool.toString());
        }
    }

    /** Findings for one tool definition. */
    public List<String> scan(JsonNode tool) {
        Set<String> out = new LinkedHashSet<>();
        walk(tool, null, (field, text) -> {
            if (INVISIBLE.matcher(text).find()) out.add("hidden_characters");
            if (field != null && TEXT_FIELDS.contains(field)) {
                String visible = INVISIBLE.matcher(text).replaceAll("");
                RULES.forEach((name, p) -> {
                    if (p.matcher(visible).find()) out.add(name);
                });
                if (text.length() > maxDescription) out.add("oversized_description");
            }
        });
        return List.copyOf(out);
    }

    interface Visitor {
        void text(String field, String text);
    }

    private static void walk(JsonNode n, String field, Visitor v) {
        if (n.isTextual()) v.text(field, n.asText());
        else if (n.isObject()) n.fields().forEachRemaining(e -> walk(e.getValue(), e.getKey(), v));
        else if (n.isArray()) n.forEach(x -> walk(x, field, v));
    }

    /** Strip invisible characters everywhere; replace suspicious description/title text. */
    private JsonNode sanitize(JsonNode n, String field) {
        if (n.isTextual()) {
            String t = INVISIBLE.matcher(n.asText()).replaceAll("");
            if (field != null && TEXT_FIELDS.contains(field)
                    && (t.length() > maxDescription || RULES.values().stream().anyMatch(p -> p.matcher(t).find()))) {
                return TextNode.valueOf(REDACTED_TEXT);
            }
            return TextNode.valueOf(t);
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                var e = it.next();
                o.set(INVISIBLE.matcher(e.getKey()).replaceAll(""), sanitize(e.getValue(), e.getKey()));
            }
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(sanitize(x, field)));
            return a;
        }
        return n;
    }

    /** Recompute the views of one upstream's tools (names already namespaced). */
    public synchronized void reload(String upstream, List<JsonNode> tools, boolean trusted) {
        Set<String> seen = new HashSet<>();
        boolean pinsChanged = false;
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            seen.add(name);
            String h = hash(t);
            String pinned = pins.get(name);
            if (pinned == null) {
                pins.put(name, h);
                pinned = h;
                pinsChanged = true;
            }
            View old = views.get(name);
            View v = evaluate(name, upstream, t, h, pinned, trusted);
            views.put(name, v);
            boolean news = old == null || !old.hash.equals(v.hash) || old.state != v.state;
            if (news && v.state == State.CHANGED) {
                log.warn("tool {} changed after it was pinned (pinned {}, now {}); quarantined", name, pinned, h);
                audit.append(JSON.createObjectNode().put("event", "tool_definition_changed").put("tool", name)
                        .put("pinned", pinned).put("current", h));
            } else if (news && !v.findings.isEmpty()) {
                log.warn("tool {} metadata flagged {}: {}", name, v.findings, v.state);
                ObjectNode rec = JSON.createObjectNode().put("event", "tool_metadata_flagged").put("tool", name).put("hash", h)
                        .put("action", v.state.name());
                v.findings.forEach(rec.putArray("findings")::add);
                audit.append(rec);
            }
        }
        views.values().removeIf(v -> v.upstream.equals(upstream) && !seen.contains(v.name));
        if (pinsChanged) savePins();
    }

    private View evaluate(String name, String upstream, JsonNode raw, String h, String pinned, boolean trusted) {
        if (!pinned.equals(h)) return new View(name, upstream, raw, raw, h, pinned, State.CHANGED, List.of("definition_changed"));
        List<String> f = scan(raw);
        if (f.isEmpty()) return new View(name, upstream, raw, raw, h, pinned, State.OK, f);
        String action = trusted ? "flag" : untrustedAction;
        return switch (action) {
            case "block" -> new View(name, upstream, raw, raw, h, pinned, State.BLOCKED, f);
            case "redact" -> new View(name, upstream, raw, sanitize(raw, null), h, pinned, State.REDACTED, f);
            default -> new View(name, upstream, raw, raw, h, pinned, State.FLAGGED, f);
        };
    }

    public Optional<View> view(String name) {
        return Optional.ofNullable(views.get(name));
    }

    public List<View> views() {
        return new ArrayList<>(new TreeMap<>(views).values());
    }

    /** An admin accepts a tool's current definition: re-pin it. */
    public synchronized Optional<View> accept(String name, String approver, boolean trusted) {
        View v = views.get(name);
        if (v == null) return Optional.empty();
        pins.put(name, v.hash);
        savePins();
        View nv = evaluate(name, v.upstream, v.raw, v.hash, v.hash, trusted);
        views.put(name, nv);
        audit.append(JSON.createObjectNode().put("event", "tool_pin_accepted").put("tool", name).put("hash", v.hash)
                .put("previous", v.pinned).put("approver", approver));
        return Optional.of(nv);
    }

    private void savePins() {
        if (pinsFile == null) return;
        try {
            ObjectNode o = JSON.createObjectNode();
            new TreeMap<>(pins).forEach(o::put);
            Path parent = pinsFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "pins", ".tmp");
            Files.writeString(tmp, o.toPrettyString());
            Files.move(tmp, pinsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.error("cannot save tool pins to {}", pinsFile, e);
        }
    }
}
