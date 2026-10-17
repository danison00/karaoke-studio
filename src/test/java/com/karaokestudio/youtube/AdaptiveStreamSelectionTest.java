package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class AdaptiveStreamSelectionTest {

    @Test
    void prefersCompatibleH264AacPairAtHighestResolutionWithinLimit() {
        var video720 = stream("video720", "video/mp4", "avc1.64001f", null, "720p", 30, 1_000, true, false, false, false);
        var video1080 = stream("video1080", "video/mp4", "avc1.640028", null, "1080p", 60, 2_000, true, false, false, false);
        var video1440 = stream("video1440", "video/mp4", "avc1.640032", null, "1440p", 60, 3_000, true, false, false, false);
        var audio128 = stream("audio128", "audio/mp4", null, "mp4a.40.2", null, 0, 128, false, false, false, true);
        var audio256 = stream("audio256", "audio/mp4", null, "mp4a.40.2", null, 0, 256, false, false, false, true);

        var pair = new AdaptiveStreamSelector().select(List.of(video720, video1080, video1440, audio128, audio256), 1080).orElseThrow();

        assertThat(pair.video()).isSameAs(video1080);
        assertThat(pair.audio()).isSameAs(audio256);
    }

    @Test
    void rejectsSabrOtfAndStreamsWithoutDirectUrls() {
        var sabrVideo = stream(null, "video/mp4", "avc1.64001f", null, "720p", 30, 1_000, true, true, false, false);
        var otfVideo = stream("otf", "video/mp4", "avc1.64001f", null, "720p", 30, 1_000, true, false, true, false);
        var video = stream("video", "video/mp4", "avc1.64001f", null, "720p", 30, 1_000, true, false, false, false);
        var audio = stream("audio", "audio/mp4", null, "mp4a.40.2", null, 0, 128, false, false, false, true);

        var pair = new AdaptiveStreamSelector().select(List.of(sabrVideo, otfVideo, video, audio), 1080).orElseThrow();

        assertThat(pair.video()).isSameAs(video);
    }

    @Test
    void returnsEmptyWhenThereIsNoCompletePair() {
        var video = stream("video", "video/mp4", "avc1.64001f", null, "720p", 30, 1_000, true, false, false, false);

        assertThat(new AdaptiveStreamSelector().select(List.of(video), 1080)).isEmpty();
    }

    @Test
    void rejectsNoCompatibleStreamsWithStableResolverError() {
        var client = new JavaTubeClient() {
            @Override public String title() { return "Título"; }
            @Override public long durationSeconds() { return 1; }
            @Override public JavaTubeStreamQuery streams() {
                return new JavaTubeStreamQuery() {
                    @Override public YoutubeStream defaultResolution() { return null; }
                    @Override public YoutubeStream onlyAudio() { return null; }
                    @Override public List<YoutubeStream> all() { return List.of(); }
                };
            }
        };

        assertThatThrownBy(() -> new JavaTubeResolver(url -> client).resolve(URI.create("https://www.youtube.com/watch?v=abcdefghijk")))
                .isInstanceOf(YoutubeResolutionException.class)
                .hasMessageContaining("Nenhum par de vídeo e áudio compatível");
    }

    private static YoutubeStream stream(
            String url,
            String contentType,
            String videoCodec,
            String audioCodec,
            String resolution,
            int fps,
            int bitrate,
            boolean progressive,
            boolean sabr,
            boolean otf,
            boolean defaultAudioTrack) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return url == null ? null : URI.create("https://media.example/" + url); }
            @Override public String contentType() { return contentType; }
            @Override public long contentLength() { return 12_000; }
            @Override public long durationMs() { return 42_000; }
            @Override public boolean progressive() { return progressive; }
            @Override public boolean sabr() { return sabr; }
            @Override public boolean otf() { return otf; }
            @Override public boolean includesVideo() { return videoCodec != null; }
            @Override public boolean includesAudio() { return audioCodec != null; }
            @Override public String videoCodec() { return videoCodec; }
            @Override public String audioCodec() { return audioCodec; }
            @Override public String resolution() { return resolution; }
            @Override public int fps() { return fps; }
            @Override public long bitrate() { return bitrate; }
            @Override public boolean defaultAudioTrack() { return defaultAudioTrack; }
            @Override public void writeTo(OutputStream output) { }
        };
    }
}
