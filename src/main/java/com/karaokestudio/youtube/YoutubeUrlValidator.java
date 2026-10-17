package com.karaokestudio.youtube;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class YoutubeUrlValidator {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final int maxUrlLength;

    public YoutubeUrlValidator(int maxUrlLength) {
        this.maxUrlLength = maxUrlLength;
    }

    public YoutubeUrl validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new YoutubeUrlException("A URL do YouTube é obrigatória");
        }
        if (rawUrl.length() > maxUrlLength) {
            throw new YoutubeUrlException("A URL do YouTube é longa demais");
        }

        final URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException exception) {
            throw new YoutubeUrlException("A URL do YouTube é inválida", exception);
        }

        var host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getFragment() != null) {
            throw new YoutubeUrlException("A URL deve ser HTTPS e pertencer a um host permitido do YouTube");
        }

        var videoId = extractVideoId(uri, host.toLowerCase(Locale.ROOT));
        if (!VIDEO_ID.matcher(videoId).matches()) {
            throw new YoutubeUrlException("O identificador do vídeo do YouTube é inválido");
        }
        return new YoutubeUrl(uri, videoId);
    }

    private static String extractVideoId(URI uri, String host) {
        if (host.equals("youtu.be")) {
            return firstPathSegment(uri.getPath());
        }

        var path = uri.getPath() == null ? "" : uri.getPath();
        if (path.equals("/watch")) {
            return queryParameter(uri.getRawQuery(), "v");
        }

        var segments = path.split("/");
        if (segments.length == 3 && (segments[1].equals("shorts") || segments[1].equals("embed"))) {
            return decode(segments[2]);
        }
        throw new YoutubeUrlException("A URL não aponta para um vídeo público do YouTube");
    }

    private static String queryParameter(String rawQuery, String expectedName) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new YoutubeUrlException("A URL do YouTube não contém o parâmetro v");
        }
        String value = null;
        for (var parameter : rawQuery.split("&", -1)) {
            var pair = parameter.split("=", 2);
            if (pair.length == 2 && decode(pair[0]).equals(expectedName)) {
                if (value != null) {
                    throw new YoutubeUrlException("A URL contém mais de um parâmetro v");
                }
                value = decode(pair[1]);
            }
        }
        if (value == null || value.isBlank()) {
            throw new YoutubeUrlException("A URL do YouTube não contém o parâmetro v");
        }
        return value;
    }

    private static String firstPathSegment(String path) {
        if (path == null || path.isBlank()) {
            throw new YoutubeUrlException("A URL curta do YouTube não contém um vídeo");
        }
        var value = path.startsWith("/") ? path.substring(1) : path;
        var separator = value.indexOf('/');
        return decode(separator >= 0 ? value.substring(0, separator) : value);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new YoutubeUrlException("A URL do YouTube contém codificação inválida", exception);
        }
    }
}
