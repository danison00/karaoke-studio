package com.karaokestudio.youtube.preparation;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.karaokestudio.youtube.HttpRange;
import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;

/**
 * Keeps signed YouTube URLs server-side and exposes only short-lived loopback
 * URLs to the local FFmpeg process.
 */
public final class YoutubeRelayRegistry {

    private static final String PATH_PREFIX = "/api/internal/youtube-relay/";
    private static final Logger log = LoggerFactory.getLogger(YoutubeRelayRegistry.class);

    private final UpstreamStreamClient upstreamClient;
    private final URI relayBaseUri;
    private final Duration tokenTtl;
    private final ConcurrentHashMap<String, RelayEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SessionGeneration, Set<String>> sessionEntries = new ConcurrentHashMap<>();

    public YoutubeRelayRegistry(UpstreamStreamClient upstreamClient, URI relayBaseUri, Duration tokenTtl) {
        if (upstreamClient == null || relayBaseUri == null || tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Configuração de relay inválida");
        }
        if (!"http".equalsIgnoreCase(relayBaseUri.getScheme())
                || relayBaseUri.getUserInfo() != null
                || relayBaseUri.getPath() != null && !relayBaseUri.getPath().isBlank() && !"/".equals(relayBaseUri.getPath())) {
            throw new IllegalArgumentException("O relay deve usar uma origem HTTP simples");
        }
        this.upstreamClient = upstreamClient;
        this.relayBaseUri = URI.create(trimTrailingSlash(relayBaseUri.toString()));
        this.tokenTtl = tokenTtl;
    }

    public RelayHandles register(UUID sessionId, long generation, YoutubeStream video, YoutubeStream audio) {
        if (sessionId == null || generation < 0 || video == null || audio == null
                || video.upstreamUri() == null || audio.upstreamUri() == null) {
            throw new IllegalArgumentException("Streams de relay inválidos");
        }
        var key = new SessionGeneration(sessionId, generation);
        var videoEntry = createEntry(key, "video", video.upstreamUri());
        var audioEntry = createEntry(key, "audio", audio.upstreamUri());
        sessionEntries.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                .add(videoEntry.token);
        sessionEntries.get(key).add(audioEntry.token);
        log.debug("youtube.relay.register sessionId={} generation={} videoHost={} audioHost={} expiresAt={}",
                sessionId, generation, video.upstreamUri().getHost(), audio.upstreamUri().getHost(),
                videoEntry.expiresAt);
        return new RelayHandles(
                uri(videoEntry.token, "video"),
                uri(audioEntry.token, "audio"),
                videoEntry.token,
                audioEntry.token);
    }

    public UpstreamStreamClient.UpstreamResponse open(
            String token,
            String mediaKind,
            String remoteAddress,
            String rangeHeader) {
        assertLoopback(remoteAddress);
        if (token == null || token.isBlank() || !("video".equals(mediaKind) || "audio".equals(mediaKind))) {
            throw new YoutubeRelayException("Relay inválido");
        }
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            HttpRange.parse(rangeHeader);
        }
        var entry = entries.get(token);
        if (entry == null || !entry.mediaKind.equals(mediaKind) || entry.expiresAt.isBefore(Instant.now())) {
            throw new YoutubeRelayException("Relay expirado ou inexistente");
        }
        log.debug("youtube.relay.open sessionId={} generation={} mediaKind={} remoteAddress={} range={}",
                entry.sessionGeneration.sessionId(), entry.sessionGeneration.generation(), mediaKind,
                remoteAddress, rangeHeader);
        try {
            var response = upstreamClient.open(entry.upstreamUri, rangeHeader);
            log.debug("youtube.relay.upstream-response sessionId={} generation={} mediaKind={} status={} contentLength={} contentType={}",
                    entry.sessionGeneration.sessionId(), entry.sessionGeneration.generation(), mediaKind,
                    response.status(), response.contentLength(), response.contentType());
            var wrapperReference = new AtomicReference<UpstreamStreamClient.UpstreamResponse>();
            var wrapped = new UpstreamStreamClient.UpstreamResponse(
                    response.status(),
                    response.contentType(),
                    response.contentLength(),
                    response.contentRange(),
                    response.acceptsRanges(),
                    response.body(),
                    () -> {
                        try {
                            response.close();
                        } finally {
                            var current = wrapperReference.get();
                            if (current != null) {
                                entry.active.remove(current);
                            }
                        }
                    });
            wrapperReference.set(wrapped);
            entry.active.add(wrapped);
            return wrapped;
        } catch (IOException exception) {
            log.warn("youtube.relay.upstream-failed sessionId={} generation={} mediaKind={} message={}",
                    entry.sessionGeneration.sessionId(), entry.sessionGeneration.generation(), mediaKind,
                    exception.getMessage(), exception);
            throw new YoutubeRelayException("Não foi possível abrir o upstream do relay", exception);
        }
    }

    public void cancel(UUID sessionId, long generation) {
        if (sessionId == null || generation < 0) return;
        var key = new SessionGeneration(sessionId, generation);
        var tokens = sessionEntries.remove(key);
        if (tokens == null) return;
        log.debug("youtube.relay.cancel sessionId={} generation={} activeTokens={}", sessionId, generation, tokens.size());
        for (var token : tokens) {
            var entry = entries.remove(token);
            if (entry != null) {
                entry.active.forEach(UpstreamStreamClient.UpstreamResponse::close);
                entry.active.clear();
            }
        }
    }

    public void discardSession(UUID sessionId) {
        if (sessionId == null) return;
        sessionEntries.keySet().stream()
                .filter(key -> key.sessionId.equals(sessionId))
                .toList()
                .forEach(key -> cancel(key.sessionId, key.generation));
    }

    public void cleanupExpired() {
        var now = Instant.now();
        entries.forEach((token, entry) -> {
            if (entry.expiresAt.isBefore(now)) {
                entries.remove(token, entry);
                entry.active.forEach(UpstreamStreamClient.UpstreamResponse::close);
                entry.active.clear();
            }
        });
    }

    private RelayEntry createEntry(SessionGeneration key, String mediaKind, URI upstreamUri) {
        var token = UUID.randomUUID().toString().replace("-", "");
        var entry = new RelayEntry(key, mediaKind, upstreamUri, token, Instant.now().plus(tokenTtl));
        entries.put(token, entry);
        return entry;
    }

    private URI uri(String token, String mediaKind) {
        return URI.create(relayBaseUri + PATH_PREFIX + token + "/" + mediaKind);
    }

    private static void assertLoopback(String remoteAddress) {
        try {
            if (remoteAddress == null || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new YoutubeRelayException("O relay aceita somente clientes locais");
            }
        } catch (IOException exception) {
            throw new YoutubeRelayException("Endereço de relay inválido", exception);
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record RelayHandles(URI video, URI audio, String videoToken, String audioToken) { }

    private record SessionGeneration(UUID sessionId, long generation) { }

    private static final class RelayEntry {
        private final SessionGeneration sessionGeneration;
        private final String mediaKind;
        private final URI upstreamUri;
        private final String token;
        private final Instant expiresAt;
        private final Set<UpstreamStreamClient.UpstreamResponse> active = ConcurrentHashMap.newKeySet();

        private RelayEntry(SessionGeneration sessionGeneration, String mediaKind, URI upstreamUri,
                String token, Instant expiresAt) {
            this.sessionGeneration = sessionGeneration;
            this.mediaKind = mediaKind;
            this.upstreamUri = upstreamUri;
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}
