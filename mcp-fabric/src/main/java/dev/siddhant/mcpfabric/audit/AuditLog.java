package dev.siddhant.mcpfabric.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only, hash-chained audit log (JSON lines). Each record carries the
 * SHA-256 of the previous record's line and its own `hash` over its content
 * plus that link, so any edit, deletion or reordering breaks verification
 * from that point on. Records are forced to disk before the call returns.
 * On open, a torn last line (crash mid-write) is truncated.
 */
public class AuditLog implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FileChannel ch;
    private String prev;
    private long seq;

    public AuditLog(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        String last = "genesis";
        long n = 0;
        long validBytes = 0;
        if (Files.exists(path)) {
            byte[] all = Files.readAllBytes(path);
            int start = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == '\n') {
                    String line = new String(all, start, i - start, StandardCharsets.UTF_8);
                    JsonNode r = JSON.readTree(line);
                    last = r.get("hash").asText();
                    n = r.get("seq").asLong();
                    start = i + 1;
                    validBytes = start;
                }
            }
        }
        ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        ch.truncate(validBytes);
        ch.position(validBytes);
        prev = last;
        seq = n;
    }

    public synchronized void append(ObjectNode rec) {
        try {
            rec.put("seq", ++seq).put("ts", Instant.now().toString()).put("prev", prev);
            rec.remove("hash");
            String h = sha256(JSON.writeValueAsString(rec));
            rec.put("hash", h);
            ch.write(ByteBuffer.wrap((JSON.writeValueAsString(rec) + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(false);
            prev = h;
        } catch (IOException e) {
            throw new IllegalStateException("audit log write failed", e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Check the whole chain; returns null if intact, else a description of the first problem. */
    public static String verify(Path path) throws IOException {
        String prev = "genesis";
        long expect = 1;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            ObjectNode r = (ObjectNode) JSON.readTree(line);
            String h = r.path("hash").asText();
            if (r.path("seq").asLong() != expect) return "record " + expect + ": sequence " + r.path("seq").asLong();
            if (!r.path("prev").asText().equals(prev)) return "record " + expect + ": broken link";
            r.remove("hash");
            if (!sha256(JSON.writeValueAsString(r)).equals(h)) return "record " + expect + ": content altered";
            prev = h;
            expect++;
        }
        return null;
    }

    @Override
    public synchronized void close() throws IOException {
        ch.close();
    }
}
