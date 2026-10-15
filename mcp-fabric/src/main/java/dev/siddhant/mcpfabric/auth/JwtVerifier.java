package dev.siddhant.mcpfabric.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.siddhant.mcpfabric.FabricProperties.Auth;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates OAuth 2.1 JWT access tokens (RFC 9068 style) for this resource
 * server: the signature against the issuer's JWKS (fetched, cached, and
 * re-fetched when an unknown `kid` appears, with a rate limit), an allow-list
 * of asymmetric algorithms (so `none` and HS256-with-the-public-key are
 * rejected), `iss` exact match, `aud` containing this resource, `exp`/`nbf`
 * with a small clock skew, and `sub` present.
 */
public class JwtVerifier {
    /** Validation failure with a coarse, client-safe reason. */
    public static class InvalidToken extends Exception {
        public final String reason;

        InvalidToken(String reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    public record Verified(JWTClaimsSet claims, Set<String> scopes) {
    }

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    private final Set<JWSAlgorithm> algorithms;

    public JwtVerifier(Auth a) {
        if (a.issuer() == null || a.jwksUri() == null || a.audiences().isEmpty()) {
            throw new IllegalStateException("fabric.auth.mode=oauth needs fabric.auth.issuer, jwks-uri and resource (or audiences)");
        }
        algorithms = a.algorithms().stream().map(JWSAlgorithm::parse).collect(Collectors.toUnmodifiableSet());
        for (JWSAlgorithm alg : algorithms) {
            if (JWSAlgorithm.Family.HMAC_SHA.contains(alg)) throw new IllegalStateException("symmetric algorithm not allowed: " + alg);
        }
        JWKSource<SecurityContext> keys;
        try {
            var builder = JWKSourceBuilder.<SecurityContext>create(URI.create(a.jwksUri()).toURL(), new DefaultResourceRetriever(3000, 3000, 256 * 1024))
                    .cache(a.jwksCacheTtl().toMillis(), 5000).retrying(false);
            long min = a.jwksMinRefreshInterval().toMillis();
            builder = min > 0 ? builder.rateLimited(min) : builder.rateLimited(false);
            keys = builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("bad jwks-uri " + a.jwksUri(), e);
        }
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));
        var claims = new DefaultJWTClaimsVerifier<SecurityContext>(new HashSet<>(a.audiences()),
                new JWTClaimsSet.Builder().issuer(a.issuer()).build(), Set.of("sub", "exp"), null);
        claims.setMaxClockSkew((int) a.clockSkew().toSeconds());
        processor.setJWTClaimsSetVerifier(claims);
    }

    public Verified verify(String token) throws InvalidToken {
        JWT jwt;
        try {
            jwt = JWTParser.parse(token);
        } catch (Exception e) {
            throw new InvalidToken("malformed", "not a JWT");
        }
        if (jwt instanceof PlainJWT) throw new InvalidToken("alg_none", "unsigned token");
        if (!(jwt instanceof SignedJWT sj)) throw new InvalidToken("malformed", "encrypted tokens are not accepted");
        if (!algorithms.contains(sj.getHeader().getAlgorithm())) {
            throw new InvalidToken("algorithm_not_allowed", "algorithm " + sj.getHeader().getAlgorithm() + " not allowed");
        }
        JWTClaimsSet c;
        try {
            c = processor.process(sj, null);
        } catch (BadJWTException e) {
            String m = String.valueOf(e.getMessage());
            String reason = m.contains("Expired") ? "expired" : m.contains("before use time") ? "not_yet_valid"
                    : m.contains("audience") ? "audience" : m.contains("iss") ? "issuer" : "claims";
            throw new InvalidToken(reason, m);
        } catch (BadJOSEException e) {
            String m = String.valueOf(e.getMessage());
            throw new InvalidToken(m.contains("no matching key") ? "unknown_key" : m.contains("Invalid signature") ? "signature" : "rejected", m);
        } catch (Exception e) {
            // JWKS fetch failures (KeySourceException) and the like
            throw new InvalidToken("key_source", String.valueOf(e.getMessage()));
        }
        return new Verified(c, scopes(c));
    }

    /** `scope` (space-separated, RFC 9068) or `scp` (array or string, some IdPs). */
    static Set<String> scopes(JWTClaimsSet c) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : List.of("scope", "scp")) {
            Object v = c.getClaim(name);
            if (v instanceof String s) out.addAll(List.of(s.trim().split("\\s+")));
            else if (v instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        }
        out.remove("");
        return out;
    }
}
