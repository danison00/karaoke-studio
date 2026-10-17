package com.karaokestudio.youtube;

import java.io.OutputStream;
import java.net.URI;

public interface YoutubeStream {

    URI upstreamUri();

    String contentType();

    long contentLength();

    long durationMs();

    boolean progressive();

    boolean sabr();

    boolean otf();

    default boolean includesVideo() {
        return progressive() || (contentType() != null && contentType().toLowerCase().startsWith("video/"));
    }

    default boolean includesAudio() {
        return progressive() || (contentType() != null && contentType().toLowerCase().startsWith("audio/"));
    }

    default String videoCodec() {
        return null;
    }

    default String audioCodec() {
        return null;
    }

    default String resolution() {
        return null;
    }

    default int fps() {
        return 0;
    }

    default long bitrate() {
        return 0;
    }

    default boolean defaultAudioTrack() {
        return false;
    }

    void writeTo(OutputStream output) throws Exception;
}
