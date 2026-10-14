package dev.siddhant.mcpfabric.upstream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP stdio transport: the server is a child process; messages are
 * newline-delimited JSON on its stdin/stdout. A process that exits is
 * restarted with exponential backoff (1 s .. 30 s).
 */
public class StdioUpstream extends Upstream {
    private final List<String> command;
    private final Map<String, String> env;
    private volatile Process proc;
    private volatile OutputStream stdin;
    private volatile boolean closed;
    private final Object writeLock = new Object();

    public StdioUpstream(String name, List<String> command, Map<String, String> env, Duration timeout, CircuitBreaker breaker) {
        super(name, timeout, breaker);
        this.command = command;
        this.env = env;
    }

    @Override
    public void start() {
        Thread.ofVirtual().name("stdio-" + name).start(this::supervise);
    }

    private void supervise() {
        long backoff = 1000;
        while (!closed) {
            try {
                var pb = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
                pb.environment().putAll(env);
                proc = pb.start();
                stdin = proc.getOutputStream();
                var reader = Thread.ofVirtual().start(() -> readLoop(proc));
                handshake();
                backoff = 1000;
                int code = proc.waitFor();
                reader.join(1000);
                if (closed) return;
                log.warn("{}: process exited with status {}; restarting", name, code);
            } catch (Exception e) {
                if (closed) return;
                log.warn("{}: {}; retrying in {} ms", name, e.toString(), backoff);
                if (proc != null) proc.destroyForcibly();
            }
            state = State.DOWN;
            connectionLost("process exited");
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void readLoop(Process p) {
        try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    receive(JSON.readTree(line));
                } catch (Exception e) {
                    log.warn("{}: bad message from server: {}", name, e.toString());
                }
            }
        } catch (Exception e) {
            log.debug("{}: read loop ended: {}", name, e.toString());
        }
        state = State.DOWN;
        connectionLost("stdout closed");
    }

    @Override
    protected void send(ObjectNode msg) throws Exception {
        OutputStream out = stdin;
        if (out == null) throw new UpstreamException(name + ": not started");
        byte[] b = (JSON.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            out.write(b);
            out.flush();
        }
    }

    @Override
    public void close() {
        closed = true;
        state = State.DOWN;
        if (proc != null) proc.destroy();
    }

    /** For tests: the current process id. */
    public long pid() {
        return proc == null ? -1 : proc.pid();
    }
}
