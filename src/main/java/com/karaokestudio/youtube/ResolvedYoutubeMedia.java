package com.karaokestudio.youtube;

public record ResolvedYoutubeMedia(
        String videoId,
        String title,
        Mode mode,
        long durationMs,
        YoutubeStream videoStream,
        YoutubeStream audioStream) {

    public enum Mode {
        PROGRESSIVE,
        ADAPTIVE
    }

    /**
     * Compatibility accessors for the current direct-stream proxy. They are
     * intentionally based on the video stream and will be removed when the
     * preparation API becomes the only media entry point.
     */
    public String contentType() {
        return videoStream == null ? null : videoStream.contentType();
    }

    public long contentLength() {
        return mode == Mode.PROGRESSIVE && videoStream != null ? videoStream.contentLength() : -1;
    }

    public java.net.URI upstreamUri() {
        return videoStream == null ? null : videoStream.upstreamUri();
    }

    public YoutubeStream stream() {
        return videoStream;
    }
}
