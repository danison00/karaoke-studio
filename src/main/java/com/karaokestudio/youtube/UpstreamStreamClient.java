package com.karaokestudio.youtube;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public interface UpstreamStreamClient {

    UpstreamResponse open(URI uri, String rangeHeader) throws IOException;

    default UpstreamResponse open(ResolvedYoutubeMedia media, String rangeHeader) throws IOException {
        if (media == null || media.upstreamUri() == null) {
            throw new UpstreamMediaException("O stream não possui uma URL upstream direta");
        }
        return open(media.upstreamUri(), rangeHeader);
    }

    final class UpstreamResponse implements AutoCloseable {
        private final int status;
        private final String contentType;
        private final long contentLength;
        private final String contentRange;
        private final boolean acceptsRanges;
        private final InputStream body;
        private final Runnable closeAction;
        private boolean closed;

        public UpstreamResponse(
                int status,
                String contentType,
                long contentLength,
                String contentRange,
                boolean acceptsRanges,
                InputStream body,
                Runnable closeAction) {
            this.status = status;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.acceptsRanges = acceptsRanges;
            this.body = body;
            this.closeAction = closeAction == null ? () -> { } : closeAction;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public long contentLength() {
            return contentLength;
        }

        public String contentRange() {
            return contentRange;
        }

        public boolean acceptsRanges() {
            return acceptsRanges;
        }

        public InputStream body() {
            return body;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // The connection is closed below even when the client cancelled the body.
            } finally {
                closeAction.run();
            }
        }
    }
}
