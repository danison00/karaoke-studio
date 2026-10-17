package com.karaokestudio.youtube.preparation;

import java.net.URI;

import com.karaokestudio.youtube.ResolvedYoutubeMedia;

public final class PreparedMedia implements AutoCloseable {

    private final PreparationMode mode;
    private final ResolvedYoutubeMedia directMedia;
    private final YoutubeMediaCache.CacheLease cacheLease;

    private PreparedMedia(PreparationMode mode, ResolvedYoutubeMedia directMedia, YoutubeMediaCache.CacheLease cacheLease) {
        this.mode = mode;
        this.directMedia = directMedia;
        this.cacheLease = cacheLease;
    }

    public static PreparedMedia progressive(ResolvedYoutubeMedia media) {
        return new PreparedMedia(PreparationMode.PROGRESSIVE, media, null);
    }

    public static PreparedMedia muxed(YoutubeMediaCache.CacheLease lease) {
        return new PreparedMedia(PreparationMode.MUXED, null, lease);
    }

    public PreparationMode mode() { return mode; }
    public String contentType() { return mode == PreparationMode.PROGRESSIVE ? directMedia.contentType() : cacheLease.media().contentType(); }
    public long contentLength() { return mode == PreparationMode.PROGRESSIVE ? directMedia.contentLength() : cacheLease.media().contentLength(); }
    public URI upstreamUri() { return mode == PreparationMode.PROGRESSIVE ? directMedia.upstreamUri() : null; }
    public ResolvedYoutubeMedia directMedia() { return directMedia; }
    public java.nio.file.Path file() { return mode == PreparationMode.MUXED ? cacheLease.media().file() : null; }

    @Override
    public void close() {
        if (cacheLease != null) {
            cacheLease.close();
        }
    }
}
