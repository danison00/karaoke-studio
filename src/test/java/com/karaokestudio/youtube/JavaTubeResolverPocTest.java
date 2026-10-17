package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class JavaTubeResolverPocTest {

    private static final URI VIDEO_URL = URI.create("https://www.youtube.com/watch?v=abcdefghijk");

    @Test
    void prefersUsableProgressiveMp4() {
        var progressive = stream("https://media.example/progressive.mp4", "video/mp4", 12_000, true, false, false);
        var audio = stream("https://media.example/audio.mp4", "audio/mp4", 8_000, false, false, false);
        var resolver = resolver(new FakeClient("Vídeo público", 42, new FakeQuery(progressive, audio)));

        var resolved = resolver.resolve(VIDEO_URL);

        assertThat(resolved.videoId()).isEqualTo("abcdefghijk");
        assertThat(resolved.title()).isEqualTo("Vídeo público");
        assertThat(resolved.mode()).isEqualTo(ResolvedYoutubeMedia.Mode.PROGRESSIVE);
        assertThat(resolved.contentType()).isEqualTo("video/mp4");
        assertThat(resolved.durationMs()).isEqualTo(42_000);
        assertThat(resolved.contentLength()).isEqualTo(12_000);
        assertThat(resolved.upstreamUri()).isEqualTo(URI.create("https://media.example/progressive.mp4"));
    }

    @Test
    void fallsBackToAdaptivePairWhenProgressiveIsUnavailable() {
        var video = stream("https://media.example/video.mp4", "video/mp4", 12_000, false, false, false);
        var audio = stream("https://media.example/audio.mp4", "audio/mp4", 8_000, false, false, false);
        var resolver = resolver(new FakeClient("Vídeo adaptativo", 9, new FakeQuery(null, audio, List.of(video, audio))));

        var resolved = resolver.resolve(VIDEO_URL);

        assertThat(resolved.mode()).isEqualTo(ResolvedYoutubeMedia.Mode.ADAPTIVE);
        assertThat(resolved.videoStream()).isSameAs(video);
        assertThat(resolved.audioStream()).isSameAs(audio);
    }

    @Test
    void fallsBackToAdaptiveWhenDefaultStreamIsSabrOrOtf() {
        var sabr = stream("https://media.example/sabr", "video/mp4", 12_000, true, true, false);
        var video = stream("https://media.example/video.mp4", "video/mp4", 12_000, false, false, false);
        var audio = stream("https://media.example/audio.mp4", "audio/mp4", 8_000, false, false, false);
        var resolver = resolver(new FakeClient("Fallback", 1, new FakeQuery(sabr, audio, List.of(sabr, video, audio))));

        var resolved = resolver.resolve(VIDEO_URL);

        assertThat(resolved.mode()).isEqualTo(ResolvedYoutubeMedia.Mode.ADAPTIVE);
        assertThat(resolved.videoStream()).isSameAs(video);
    }

    @Test
    void rejectsWhenNoBrowserPlayableStreamExists() {
        var resolver = resolver(new FakeClient("Sem mídia", 1, new FakeQuery(null, null)));

        assertThatThrownBy(() -> resolver.resolve(VIDEO_URL))
                .isInstanceOf(YoutubeResolutionException.class)
                .hasMessageContaining("Nenhum par de vídeo e áudio compatível");
    }

    @Test
    void wrapsJavaTubeFailuresInStableException() {
        var resolver = new JavaTubeResolver(url -> {
            throw new Exception("upstream failed");
        });

        assertThatThrownBy(() -> resolver.resolve(VIDEO_URL))
                .isInstanceOf(YoutubeResolutionException.class)
                .hasMessageContaining("Não foi possível resolver o vídeo")
                .hasRootCauseMessage("upstream failed");
    }

    private static JavaTubeResolver resolver(FakeClient client) {
        return new JavaTubeResolver(url -> client);
    }

    private static YoutubeStream stream(
            String url,
            String contentType,
            long contentLength,
            boolean progressive,
            boolean sabr,
            boolean otf) {
        return new YoutubeStream() {
            @Override
            public URI upstreamUri() {
                return URI.create(url);
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public long durationMs() {
                return 42_000;
            }

            @Override
            public boolean progressive() {
                return progressive;
            }

            @Override
            public boolean sabr() {
                return sabr;
            }

            @Override
            public boolean otf() {
                return otf;
            }

            @Override
            public void writeTo(OutputStream output) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record FakeClient(String title, long durationSeconds, JavaTubeStreamQuery query)
            implements JavaTubeClient {
        @Override
        public String title() {
            return title;
        }

        @Override
        public long durationSeconds() {
            return durationSeconds;
        }

        @Override
        public JavaTubeStreamQuery streams() {
            return query;
        }
    }

    private record FakeQuery(YoutubeStream progressive, YoutubeStream audio, List<YoutubeStream> all) implements JavaTubeStreamQuery {
        private FakeQuery(YoutubeStream progressive, YoutubeStream audio) {
            this(progressive, audio, java.util.stream.Stream.of(progressive, audio).filter(java.util.Objects::nonNull).toList());
        }

        @Override
        public YoutubeStream defaultResolution() {
            return progressive;
        }

        @Override
        public YoutubeStream onlyAudio() {
            return audio;
        }

        @Override
        public List<YoutubeStream> all() {
            return all;
        }
    }
}
