package com.karaokestudio.youtube;

import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.github.felipeucelli.javatube.Stream;
import com.github.felipeucelli.javatube.StreamQuery;
import com.github.felipeucelli.javatube.Youtube;

public class JavaTubeResolver {

    private static final String JAVA_TUBE_CLIENT = "WEB";
    private static final boolean JAVA_TUBE_USE_PO_TOKEN = true;
    private static final boolean JAVA_TUBE_ALLOW_CACHE = false;

    private final ClientFactory clientFactory;
    private final AdaptiveStreamSelector adaptiveSelector;
    private final int maxHeight;

    public JavaTubeResolver() {
        this(JavaTubeClientAdapter::new, new AdaptiveStreamSelector(), 1080);
    }

    public JavaTubeResolver(ClientFactory clientFactory) {
        this(clientFactory, new AdaptiveStreamSelector(), 1080);
    }

    public JavaTubeResolver(ClientFactory clientFactory, AdaptiveStreamSelector adaptiveSelector) {
        this(clientFactory, adaptiveSelector, 1080);
    }

    public JavaTubeResolver(int maxHeight) {
        this(JavaTubeClientAdapter::new, new AdaptiveStreamSelector(), maxHeight);
    }

    public JavaTubeResolver(ClientFactory clientFactory, AdaptiveStreamSelector adaptiveSelector, int maxHeight) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
        this.adaptiveSelector = Objects.requireNonNull(adaptiveSelector);
        if (maxHeight <= 0) {
            throw new IllegalArgumentException("A altura máxima deve ser positiva");
        }
        this.maxHeight = maxHeight;
    }

    public ResolvedYoutubeMedia resolve(URI url) {
        var videoId = extractVideoId(url);
        try {
            var client = clientFactory.create(url.toString());
            var query = Objects.requireNonNull(client.streams(), "JavaTube não retornou streams");
            var progressive = tryDefaultResolution(query);
            if (isUsableProgressive(progressive)) {
                return map(videoId, client, ResolvedYoutubeMedia.Mode.PROGRESSIVE, progressive, null);
            }

            var adaptive = adaptiveSelector.select(tryAll(query), maxHeight);
            if (adaptive.isPresent()) {
                return map(videoId, client, ResolvedYoutubeMedia.Mode.ADAPTIVE,
                        adaptive.get().video(), adaptive.get().audio());
            }

            throw new YoutubeResolutionException("Nenhum par de vídeo e áudio compatível foi encontrado para o vídeo");
        } catch (YoutubeResolutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new YoutubeResolutionException("Não foi possível resolver o vídeo do YouTube", exception);
        }
    }

    static Youtube createYoutube(String url) throws Exception {
        return new Youtube(url, JAVA_TUBE_CLIENT, JAVA_TUBE_USE_PO_TOKEN, JAVA_TUBE_ALLOW_CACHE);
    }

    private ResolvedYoutubeMedia map(
            String videoId,
            JavaTubeClient client,
            ResolvedYoutubeMedia.Mode mode,
            YoutubeStream video,
            YoutubeStream audio) throws Exception {
        var title = client.title();
        if (title == null || title.isBlank()) {
            title = videoId;
        }
        var durationMs = Math.max(0, client.durationSeconds() * 1_000L);
        if (durationMs == 0) {
            durationMs = Math.max(0, video == null ? 0 : video.durationMs());
        }
        return new ResolvedYoutubeMedia(
                videoId,
                title,
                mode,
                durationMs,
                video,
                audio);
    }

    private static YoutubeStream tryDefaultResolution(JavaTubeStreamQuery query) {
        try {
            return query.defaultResolution();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static java.util.List<YoutubeStream> tryAll(JavaTubeStreamQuery query) {
        try {
            return query.all();
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }

    private static boolean isUsableProgressive(YoutubeStream stream) {
        return stream != null
                && stream.progressive()
                && !stream.sabr()
                && !stream.otf()
                && stream.upstreamUri() != null
                && normalizeContentType(stream.contentType()).toLowerCase().startsWith("video/mp4");
    }

    private static String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private static String extractVideoId(URI url) {
        var host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (host.equals("youtu.be")) {
            return firstPathSegment(url.getPath());
        }
        var query = url.getRawQuery();
        if (query != null) {
            for (var parameter : query.split("&")) {
                var pair = parameter.split("=", 2);
                if (pair.length == 2 && pair[0].equals("v")) {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }
        var path = url.getPath() == null ? "" : url.getPath();
        var segments = path.split("/");
        for (var index = 0; index < segments.length - 1; index++) {
            if (segments[index].equals("shorts") || segments[index].equals("embed")) {
                return segments[index + 1];
            }
        }
        return "unknown";
    }

    private static String firstPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        var value = path.startsWith("/") ? path.substring(1) : path;
        var separator = value.indexOf('/');
        return separator >= 0 ? value.substring(0, separator) : value;
    }

    @FunctionalInterface
    public interface ClientFactory {
        JavaTubeClient create(String url) throws Exception;
    }

    private static final class JavaTubeClientAdapter implements JavaTubeClient {
        private final Youtube youtube;

        private JavaTubeClientAdapter(String url) throws Exception {
            this.youtube = createYoutube(url);
        }

        @Override
        public String title() throws Exception {
            return youtube.getTitle();
        }

        @Override
        public long durationSeconds() throws Exception {
            var length = youtube.length();
            return length == null ? 0 : length;
        }

        @Override
        public JavaTubeStreamQuery streams() throws Exception {
            return new JavaTubeStreamQueryAdapter(youtube.streams());
        }
    }

    private static final class JavaTubeStreamQueryAdapter implements JavaTubeStreamQuery {
        private final StreamQuery query;

        private JavaTubeStreamQueryAdapter(StreamQuery query) {
            this.query = query;
        }

        @Override
        public YoutubeStream defaultResolution() {
            return adapt(query.getDefaultResolution());
        }

        @Override
        public YoutubeStream onlyAudio() {
            return adapt(query.getOnlyAudio());
        }

        @Override
        public java.util.List<YoutubeStream> all() {
            return query.getAll().stream().map(JavaTubeResolver::adapt).toList();
        }
    }

    private static YoutubeStream adapt(Stream stream) {
        if (stream == null) {
            return null;
        }
        return new YoutubeStream() {
            @Override
            public URI upstreamUri() {
                var url = stream.getUrl();
                return url == null || url.isBlank() ? null : URI.create(url);
            }

            @Override
            public String contentType() {
                return stream.getMimeType();
            }

            @Override
            public long contentLength() {
                return stream.getFileSize();
            }

            @Override
            public long durationMs() {
                return stream.getDurationMs();
            }

            @Override
            public boolean progressive() {
                return Boolean.TRUE.equals(stream.isProgressive());
            }

            @Override
            public boolean sabr() {
                return Boolean.TRUE.equals(stream.getIsSabr());
            }

            @Override
            public boolean otf() {
                return Boolean.TRUE.equals(stream.getIsOtf());
            }

            @Override
            public boolean includesVideo() {
                return Boolean.TRUE.equals(stream.includeVideoTrack());
            }

            @Override
            public boolean includesAudio() {
                return Boolean.TRUE.equals(stream.includeAudioTrack());
            }

            @Override
            public String videoCodec() {
                return stream.getVideoCodec();
            }

            @Override
            public String audioCodec() {
                return stream.getAudioCodec();
            }

            @Override
            public String resolution() {
                return stream.getResolution();
            }

            @Override
            public int fps() {
                return stream.getFps() == null ? 0 : stream.getFps();
            }

            @Override
            public long bitrate() {
                return stream.getBitrate() == null ? 0 : stream.getBitrate();
            }

            @Override
            public boolean defaultAudioTrack() {
                return stream.isDefaultAudioTrack();
            }

            @Override
            public void writeTo(OutputStream output) throws Exception {
                stream.download(output);
            }
        };
    }
}
