package com.karaokestudio.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class RequestLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxGlobalStreams;
    private final int maxStreamsPerIp;
    private final int maxResolutionsPerMinute;
    private final Clock clock;
    private final Map<String, Integer> activeByIp = new HashMap<>();
    private final Map<String, ArrayDeque<Instant>> resolutionsByIp = new HashMap<>();
    private int activeGlobalStreams;

    public RequestLimiter(int maxGlobalStreams, int maxStreamsPerIp, int maxResolutionsPerMinute) {
        this(maxGlobalStreams, maxStreamsPerIp, maxResolutionsPerMinute, Clock.systemUTC());
    }

    public RequestLimiter(int maxGlobalStreams, int maxStreamsPerIp, int maxResolutionsPerMinute, Clock clock) {
        if (maxGlobalStreams <= 0 || maxStreamsPerIp <= 0 || maxResolutionsPerMinute <= 0) {
            throw new IllegalArgumentException("Os limites devem ser positivos");
        }
        this.maxGlobalStreams = maxGlobalStreams;
        this.maxStreamsPerIp = maxStreamsPerIp;
        this.maxResolutionsPerMinute = maxResolutionsPerMinute;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized Lease acquire(String ipAddress) {
        var ip = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
        var now = clock.instant();
        var resolutions = resolutionsByIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
        while (!resolutions.isEmpty() && !resolutions.peekFirst().isAfter(now.minus(WINDOW))) {
            resolutions.removeFirst();
        }
        if (resolutions.size() >= maxResolutionsPerMinute) {
            throw new RequestLimitException("Limite de resoluções por minuto excedido");
        }
        if (activeByIp.getOrDefault(ip, 0) >= maxStreamsPerIp) {
            throw new RequestLimitException("Limite de streams simultâneos por IP excedido");
        }
        if (activeGlobalStreams >= maxGlobalStreams) {
            throw new RequestLimitException("Limite de streams simultâneos globais excedido");
        }

        resolutions.addLast(now);
        activeByIp.merge(ip, 1, Integer::sum);
        activeGlobalStreams++;
        return new Lease(ip);
    }

    private synchronized void release(String ipAddress) {
        var active = activeByIp.getOrDefault(ipAddress, 0);
        if (active <= 1) {
            activeByIp.remove(ipAddress);
        } else {
            activeByIp.put(ipAddress, active - 1);
        }
        activeGlobalStreams = Math.max(0, activeGlobalStreams - 1);
    }

    public final class Lease implements AutoCloseable {
        private final String ipAddress;
        private boolean closed;

        private Lease(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(ipAddress);
            }
        }
    }
}
