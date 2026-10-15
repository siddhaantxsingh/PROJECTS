package dev.siddhant.mcpfabric.guard;

import com.fasterxml.jackson.databind.JsonNode;
import dev.siddhant.mcpfabric.FabricProperties.UpstreamConfig;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Checks the file-path arguments an upstream declares (`path-arguments`)
 * before a call is forwarded: no NUL bytes, no `..` segments (also when
 * percent-encoded or written with backslashes), and, if `path-roots` is set,
 * the normalised path must stay inside one of the roots. This is defence in
 * depth; the upstream must still confine its own file access.
 */
public final class PathGuard {
    private PathGuard() {
    }

    /** null if the arguments are acceptable, else the reason. */
    public static String check(UpstreamConfig cfg, JsonNode args) {
        if (cfg == null || cfg.pathArguments() == null) return null;
        for (String name : cfg.pathArguments()) {
            JsonNode v = args.get(name);
            if (v == null || v.isNull()) continue;
            if (!v.isTextual()) return name + " must be a string";
            String why = checkOne(v.asText(), cfg.pathRoots());
            if (why != null) return name + ": " + why;
        }
        return null;
    }

    static String checkOne(String raw, java.util.List<String> roots) {
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = raw;
        }
        for (String s : new String[] {raw, decoded}) {
            if (s.indexOf('\0') >= 0) return "NUL byte in path";
            for (String seg : s.split("[/\\\\]")) if (seg.equals("..")) return "path traversal ('..')";
        }
        if (roots == null || roots.isEmpty()) return null;
        Path p;
        try {
            p = Path.of(raw);
        } catch (InvalidPathException e) {
            return "invalid path";
        }
        for (String r : roots) {
            Path root = Path.of(r).toAbsolutePath().normalize();
            Path resolved = root.resolve(p).normalize();
            if (resolved.startsWith(root)) return null;
        }
        return "outside the allowed roots";
    }
}
