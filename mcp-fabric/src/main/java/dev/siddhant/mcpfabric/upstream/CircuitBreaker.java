package dev.siddhant.mcpfabric.upstream;

import java.time.Clock;
import java.time.Duration;

/**
 * Consecutive-failure circuit breaker. After `threshold` failures in a row
 * the circuit opens and requests fail fast; after `cooldown` one request is
 * let through (half-open): success closes the circuit, failure reopens it.
 */
public class CircuitBreaker {
    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private int failures;
    private long openedAt = -1;
    private boolean probing;

    public CircuitBreaker(int threshold, Duration cooldown, Clock clock) {
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized boolean allows() {
        if (openedAt < 0) return true;
        if (clock.millis() - openedAt >= cooldown.toMillis() && !probing) {
            probing = true;  // half-open: one trial request
            return true;
        }
        return false;
    }

    public synchronized boolean isOpen() {
        return openedAt >= 0;
    }

    public synchronized void success() {
        failures = 0;
        openedAt = -1;
        probing = false;
    }

    public synchronized void failure() {
        failures++;
        if (probing || failures >= threshold) {
            openedAt = clock.millis();
            probing = false;
        }
    }
}
