package dev.siddhant.mcpfabric.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies over `fabric.max-request-bytes` with 413, whether
 * the size is declared (Content-Length) or not (chunked): an undeclared body
 * is read up to the limit plus one byte and refused if it goes further.
 */
public class RequestSizeFilter extends OncePerRequestFilter {
    private final long max;

    public RequestSizeFilter(long max) {
        this.max = max;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(req.getMethod()) && !"PUT".equals(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }
        long declared = req.getContentLengthLong();
        if (declared > max) {
            tooLarge(resp);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(req, resp);
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = req.getInputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > max) {
                    tooLarge(resp);
                    return;
                }
            }
        }
        byte[] body = buf.toByteArray();
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() {
                var in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public boolean isFinished() { return in.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                    public int read() { return in.read(); }
                    public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
            }

            @Override
            public int getContentLength() { return body.length; }

            @Override
            public long getContentLengthLong() { return body.length; }
        }, resp);
    }

    private void tooLarge(HttpServletResponse resp) throws IOException {
        resp.setStatus(413);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"request body exceeds " + max + " bytes\"}}");
    }
}
