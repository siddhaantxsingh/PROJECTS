package dev.siddhant.mcpfabric.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.mcpfabric.audit.AuditLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Human-in-the-loop approval for sensitive tools. A matching call is parked
 * as PENDING (the client's HTTP request waits), an admin approves or denies
 * it through the admin API, and the call then proceeds or fails. Undecided
 * requests expire after the timeout. Decisions are idempotent: repeating the
 * same decision returns the recorded outcome; a conflicting decision, or one
 * on an expired request, is refused. The requester cannot approve their own
 * call. Every step is written to the audit log.
 */
public class Approvals {
    public enum Status { PENDING, APPROVED, DENIED, EXPIRED }

    public enum Outcome { APPLIED, ALREADY, CONFLICT, NOT_FOUND, SELF_APPROVAL }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SHOWN_ARGS = 4096;

    public static final class Request {
        public final String id;
        public final String client, subject, session, tool, argsSha256, argsPreview;
        public final Instant created, expires;
        volatile Status status = Status.PENDING;
        volatile String decidedBy, reason;
        final CompletableFuture<Status> done = new CompletableFuture<>();

        Request(String client, String subject, String session, String tool, String argsSha256, String argsPreview, Instant created, Instant expires) {
            this.id = UUID.randomUUID().toString();
            this.client = client;
            this.subject = subject;
            this.session = session;
            this.tool = tool;
            this.argsSha256 = argsSha256;
            this.argsPreview = argsPreview;
            this.created = created;
            this.expires = expires;
        }

        public Status status() { return status; }
        public String decidedBy() { return decidedBy; }

        public ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode().put("id", id).put("status", status.name()).put("client", client).put("subject", subject)
                    .put("tool", tool).put("args_sha256", argsSha256).put("arguments", argsPreview)
                    .put("created", created.toString()).put("expires", expires.toString());
            if (decidedBy != null) o.put("decided_by", decidedBy);
            if (reason != null) o.put("reason", reason);
            return o;
        }
    }

    public record Decision(Outcome outcome, Request request) {
    }

    private final List<Pattern> required;
    private final Duration timeout;
    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    public Approvals(List<Pattern> required, Duration timeout, AuditLog audit, Clock clock) {
        this.required = required;
        this.timeout = timeout;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean required(String tool) {
        return required.stream().anyMatch(p -> p.matcher(tool).matches());
    }

    public Request open(String client, String subject, String session, String tool, String argsSha256, JsonNode args) {
        Instant now = clock.instant();
        // forget decided requests after an hour (they are in the audit log)
        requests.values().removeIf(r -> r.status != Status.PENDING && r.expires.plus(Duration.ofHours(1)).isBefore(now));
        String preview = args.toString();
        if (preview.length() > MAX_SHOWN_ARGS) preview = preview.substring(0, MAX_SHOWN_ARGS) + "...";
        var r = new Request(client, subject, session, tool, argsSha256, preview, now, now.plus(timeout));
        requests.put(r.id, r);
        audit.append(JSON.createObjectNode().put("event", "approval_requested").put("approval", r.id).put("client", client)
                .put("subject", subject).put("session", session).put("tool", tool).put("args_sha256", argsSha256));
        return r;
    }

    /** Block until the request is decided or expires. */
    public Status await(Request r) {
        try {
            return r.done.get(Math.max(0, Duration.between(clock.instant(), r.expires).toMillis()), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            synchronized (r) {
                if (r.status == Status.PENDING) {
                    r.status = Status.EXPIRED;
                    r.done.complete(Status.EXPIRED);
                    audit.append(JSON.createObjectNode().put("event", "approval_expired").put("approval", r.id).put("tool", r.tool)
                            .put("client", r.client));
                }
            }
            return r.status;
        }
    }

    public Decision decide(String id, boolean approve, String approver, String reason) {
        Request r = requests.get(id);
        if (r == null) return new Decision(Outcome.NOT_FOUND, null);
        Status want = approve ? Status.APPROVED : Status.DENIED;
        synchronized (r) {
            if (r.status == want) return new Decision(Outcome.ALREADY, r);
            if (r.status != Status.PENDING) return new Decision(Outcome.CONFLICT, r);
            if (clock.instant().isAfter(r.expires)) return new Decision(Outcome.CONFLICT, r); // too late; the waiter marks it EXPIRED
            if (approve && approver.equals(r.subject)) return new Decision(Outcome.SELF_APPROVAL, r);
            r.status = want;
            r.decidedBy = approver;
            r.reason = reason;
            audit.append(JSON.createObjectNode().put("event", "approval_decided").put("approval", r.id).put("decision", want.name())
                    .put("approver", approver).put("tool", r.tool).put("client", r.client).put("args_sha256", r.argsSha256));
            r.done.complete(want);
            return new Decision(Outcome.APPLIED, r);
        }
    }

    public Collection<Request> all() {
        return requests.values();
    }
}
