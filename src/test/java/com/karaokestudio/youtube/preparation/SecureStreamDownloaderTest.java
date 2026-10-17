package com.karaokestudio.youtube.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.YoutubeStream;

class SecureStreamDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsStreamIncrementallyAndReportsByteCount() throws Exception {
        var client = new FakeClient("payload".getBytes());
        var downloader = new SecureStreamDownloader(client);
        var destination = tempDir.resolve("video.part");

        var count = downloader.download(stream(), destination, 100);

        assertThat(count).isEqualTo(7);
        assertThat(Files.readString(destination)).isEqualTo("payload");
    }

    @Test
    void deletesPartialFileWhenTheByteLimitIsExceeded() {
        var client = new FakeClient("payload".getBytes());
        var downloader = new SecureStreamDownloader(client);
        var destination = tempDir.resolve("audio.part");

        assertThatThrownBy(() -> downloader.download(stream(), destination, 3))
                .isInstanceOf(MediaTooLargeException.class);
        assertThat(Files.exists(destination)).isFalse();
    }

    private static YoutubeStream stream() {
        return new YoutubeStream() {
            @Override public URI upstreamUri() { return URI.create("https://rr.example/video"); }
            @Override public String contentType() { return "video/mp4"; }
            @Override public long contentLength() { return -1; }
            @Override public long durationMs() { return 1_000; }
            @Override public boolean progressive() { return false; }
            @Override public boolean sabr() { return false; }
            @Override public boolean otf() { return false; }
            @Override public boolean includesVideo() { return true; }
            @Override public void writeTo(OutputStream output) { }
        };
    }

    private static final class FakeClient implements UpstreamStreamClient {
        private final byte[] payload;

        private FakeClient(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public UpstreamResponse open(URI uri, String rangeHeader) {
            return new UpstreamResponse(200, "video/mp4", payload.length, null, false,
                    new ByteArrayInputStream(payload), () -> { });
        }
    }
}
