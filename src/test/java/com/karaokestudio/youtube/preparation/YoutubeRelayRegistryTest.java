package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;

class YoutubeRelayRegistryTest {

    private static final URI UPSTREAM_VIDEO = URI.create("https://r1---sn.googlevideo.com/videoplayback?sig=secret");
    private static final URI UPSTREAM_AUDIO = URI.create("https://r1---sn.googlevideo.com/audioplayback?sig=secret");

    @Test
    void registersOpaqueLoopbackHandlesAndOpensValidatedUpstream() throws Exception {
        var upstream = new FakeUpstream();
        upstream.next = response("video/mp4", "video");
        var registry = new YoutubeRelayRegistry(upstream, URI.create("http://127.0.0.1:8080"), Duration.ofMinutes(2));
        var handles = registry.register(UUID.randomUUID(), 4, stream(UPSTREAM_VIDEO), stream(UPSTREAM_AUDIO));

        assertThat(handles.video().getPath()).startsWith("/api/internal/youtube-relay/");
        assertThat(handles.video().toString()).doesNotContain("googlevideo").doesNotContain("sig=secret");
        assertThat(handles.audio().toString()).doesNotContain("googlevideo");

        try (var opened = registry.open(handles.videoToken(), "video", "127.0.0.1", "bytes=10-19")) {
            assertThat(opened.contentType()).isEqualTo("video/mp4");
            assertThat(opened.contentLength()).isEqualTo(5);
        }
        assertThat(upstream.lastUri).isEqualTo(UPSTREAM_VIDEO);
        assertThat(upstream.lastRange).isEqualTo("bytes=10-19");
    }

    @Test
    void rejectsRemoteClientsUnknownTokensAndInvalidRanges() {
        var registry = new YoutubeRelayRegistry(new FakeUpstream(),
                URI.create("http://127.0.0.1:8080"), Duration.ofMinutes(2));
        var handles = registry.register(UUID.randomUUID(), 1, stream(UPSTREAM_VIDEO), stream(UPSTREAM_AUDIO));

        assertThatThrownBy(() -> registry.open(handles.videoToken(), "video", "192.168.1.10", null))
                .isInstanceOf(YoutubeRelayException.class);
        assertThatThrownBy(() -> registry.open("unknown", "video", "127.0.0.1", null))
                .isInstanceOf(YoutubeRelayException.class);
        assertThatThrownBy(() -> registry.open(handles.videoToken(), "video", "127.0.0.1", "bytes=20-10"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancelClosesActiveResponseAndInvalidatesBothHandles() throws Exception {
        var upstream = new FakeUpstream();
        var closed = new AtomicBoolean();
        upstream.next = new UpstreamStreamClient.UpstreamResponse(
                200, "video/mp4", 5, null, true,
                new ByteArrayInputStream("video".getBytes()), () -> closed.set(true));
        var sessionId = UUID.randomUUID();
        var registry = new YoutubeRelayRegistry(upstream, URI.create("http://127.0.0.1:8080"), Duration.ofMinutes(2));
        var handles = registry.register(sessionId, 2, stream(UPSTREAM_VIDEO), stream(UPSTREAM_AUDIO));
        var opened = registry.open(handles.videoToken(), "video", "127.0.0.1", null);

        registry.cancel(sessionId, 2);

        assertThat(closed.get()).isTrue();
        assertThatThrownBy(() -> registry.open(handles.audioToken(), "audio", "127.0.0.1", null))
                .isInstanceOf(YoutubeRelayException.class);
    }

    private static YoutubeStream stream(URI uri) {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return uri; }
            @Override public String contentType() { return "video/mp4"; }
            @Override public long contentLength() { return 100; }
            @Override public long durationMs() { return 10_000; }
            @Override public boolean progressive() { return false; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public void writeTo(java.io.OutputStream output) { }
        };
    }

    private static UpstreamStreamClient.UpstreamResponse response(String contentType, String value) {
        return new UpstreamStreamClient.UpstreamResponse(
                200, contentType, value.length(), null, true,
                new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), () -> { });
    }

    private static final class FakeUpstream implements UpstreamStreamClient {
        private UpstreamStreamClient.UpstreamResponse next;
        private URI lastUri;
        private String lastRange;

        @Override
        public UpstreamStreamClient.UpstreamResponse open(URI uri, String rangeHeader) {
            lastUri = uri;
            lastRange = rangeHeader;
            return next;
        }
    }
}
