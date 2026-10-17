package com.karaokestudio.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RequestLimiterTest {

    @Test
    void limitsConcurrentStreamsPerIpAndGlobal() {
        var limiter = new RequestLimiter(2, 2, 10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var first = limiter.acquire("203.0.113.10");
        var second = limiter.acquire("203.0.113.10");

        assertThatThrownBy(() -> limiter.acquire("203.0.113.10"))
                .isInstanceOf(RequestLimitException.class)
                .hasMessageContaining("simultâneos");

        first.close();
        second.close();
    }

    @Test
    void limitsGlobalConcurrencyAcrossIps() {
        var limiter = new RequestLimiter(2, 8, 10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var first = limiter.acquire("203.0.113.10");
        var second = limiter.acquire("203.0.113.11");

        assertThatThrownBy(() -> limiter.acquire("203.0.113.12"))
                .isInstanceOf(RequestLimitException.class)
                .hasMessageContaining("globais");

        first.close();
        second.close();
    }

    @Test
    void limitsResolutionsWithinTheConfiguredWindow() {
        var limiter = new RequestLimiter(8, 8, 2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        try (var first = limiter.acquire("203.0.113.10")) {
            // The lease controls concurrency; the resolution counter remains in the window after close.
        }
        try (var second = limiter.acquire("203.0.113.10")) {
            // The second resolution is still allowed.
        }

        assertThatThrownBy(() -> limiter.acquire("203.0.113.10"))
                .isInstanceOf(RequestLimitException.class)
                .hasMessageContaining("resoluções");
    }

    @Test
    void closeIsIdempotentAndDoesNotLeakCapacity() {
        var limiter = new RequestLimiter(1, 1, 10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var lease = limiter.acquire("203.0.113.10");
        lease.close();
        lease.close();

        assertThatCode(() -> {
            try (var ignored = limiter.acquire("203.0.113.10")) {
                // capacity was returned
            }
        }).doesNotThrowAnyException();
    }
}
