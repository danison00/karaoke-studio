package com.karaokestudio.youtube;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.karaokestudio.youtube.preparation.PreparedMedia;
import com.karaokestudio.youtube.preparation.PreparationMode;
import com.karaokestudio.web.RequestLimiter;

@Service
public class YoutubeMediaService {

    private final UpstreamHostPolicy hostPolicy;
    private final RequestLimiter requestLimiter;
    private final UpstreamStreamClient upstreamClient;

    public YoutubeMediaService(
            UpstreamHostPolicy hostPolicy,
            RequestLimiter requestLimiter,
            UpstreamStreamClient upstreamClient) {
        this.hostPolicy = Objects.requireNonNull(hostPolicy);
        this.requestLimiter = Objects.requireNonNull(requestLimiter);
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
    }

    public MediaStream open(PreparedMedia prepared, String rangeHeader, String clientIp) {
        Objects.requireNonNull(prepared);
        if (prepared.mode() != PreparationMode.PROGRESSIVE || prepared.directMedia() == null) {
            throw new IllegalArgumentException("Somente mídia progressiva pode ser aberta como upstream");
        }
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            HttpRange.parse(rangeHeader);
        }

        var media = prepared.directMedia();
        var lease = requestLimiter.acquire(clientIp);
        UpstreamStreamClient.UpstreamResponse response = null;
        try {
            hostPolicy.assertAllowed(media.upstreamUri());
            response = upstreamClient.open(media, rangeHeader);
            if (response.status() >= 400) {
                throw new UpstreamMediaException("O upstream recusou a mídia com HTTP " + response.status());
            }
            var contentType = response.contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = media.contentType();
            }
            var openedResponse = response;
            return new MediaStream(
                    response.status(),
                    contentType,
                    response.contentLength(),
                    response.contentRange(),
                    response.acceptsRanges(),
                    PreparationMode.PROGRESSIVE,
                    response.body(),
                    () -> {
                        openedResponse.close();
                        lease.close();
                    },
                    null);
        } catch (IOException exception) {
            if (response != null) {
                response.close();
            }
            lease.close();
            throw new UpstreamMediaException("Não foi possível abrir a mídia upstream", exception);
        } catch (RuntimeException exception) {
            if (response != null) {
                response.close();
            }
            lease.close();
            throw exception;
        }
    }

    public static final class MediaStream implements AutoCloseable {
        private final int status;
        private final String contentType;
        private final long contentLength;
        private final String contentRange;
        private final boolean acceptsRanges;
        private final PreparationMode mode;
        private final InputStream body;
        private final Runnable closeAction;
        private final String etag;
        private boolean closed;

        public MediaStream(
                int status,
                String contentType,
                long contentLength,
                String contentRange,
                boolean acceptsRanges,
                PreparationMode mode,
                InputStream body,
                Runnable closeAction,
                String etag) {
            this.status = status;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.acceptsRanges = acceptsRanges;
            this.mode = mode;
            this.body = body;
            this.closeAction = closeAction == null ? () -> { } : closeAction;
            this.etag = etag;
        }

        public int status() { return status; }
        public String contentType() { return contentType; }
        public long contentLength() { return contentLength; }
        public String contentRange() { return contentRange; }
        public boolean acceptsRanges() { return acceptsRanges; }
        public PreparationMode mode() { return mode; }
        public InputStream body() { return body; }
        public String etag() { return etag; }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // The limiter/cache lease is still released below.
            } finally {
                closeAction.run();
            }
        }
    }
}
