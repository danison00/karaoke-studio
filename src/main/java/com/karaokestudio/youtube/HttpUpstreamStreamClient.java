package com.karaokestudio.youtube;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpUpstreamStreamClient implements UpstreamStreamClient {

    private static final int MAX_REDIRECTS = 3;
    private static final Logger log = LoggerFactory.getLogger(HttpUpstreamStreamClient.class);

    private final UpstreamHostPolicy hostPolicy;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public HttpUpstreamStreamClient(UpstreamHostPolicy hostPolicy) {
        this(hostPolicy, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public HttpUpstreamStreamClient(
            UpstreamHostPolicy hostPolicy,
            Duration connectTimeout,
            Duration readTimeout) {
        this.hostPolicy = hostPolicy;
        this.connectTimeoutMillis = timeoutMillis(connectTimeout);
        this.readTimeoutMillis = timeoutMillis(readTimeout);
    }

    @Override
    public UpstreamResponse open(URI uri, String rangeHeader) throws IOException {
        if (uri == null) {
            throw new UpstreamMediaException("O stream não possui uma URL upstream direta");
        }
        var current = uri;
        for (var redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            hostPolicy.assertAllowed(current);
            log.debug("youtube.upstream.request host={} range={} redirect={}",
                    current.getHost(), rangeHeader, redirect);
            var connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                HttpRange.parse(rangeHeader);
                connection.setRequestProperty("Range", rangeHeader);
            }
            connection.connect();
            var status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                var location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank() || redirect == MAX_REDIRECTS) {
                    throw new UpstreamMediaException("Redirecionamento upstream não permitido");
                }
                current = current.resolve(location);
                continue;
            }

            var body = responseBody(connection, status);
            if (status >= 400) {
                log.warn("youtube.upstream.response-error host={} status={} contentLength={} contentType={}",
                        current.getHost(), status, connection.getContentLengthLong(), connection.getContentType());
            } else {
                log.debug("youtube.upstream.response host={} status={} contentLength={} contentType={} acceptsRanges={}",
                        current.getHost(), status, connection.getContentLengthLong(), connection.getContentType(),
                        "bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges")));
            }
            return new UpstreamResponse(
                    status,
                    connection.getContentType(),
                    connection.getContentLengthLong(),
                    connection.getHeaderField("Content-Range"),
                    "bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges")),
                    body,
                    connection::disconnect);
        }
        throw new UpstreamMediaException("Redirecionamento upstream excedeu o limite");
    }

    private static InputStream responseBody(HttpURLConnection connection, int status) throws IOException {
        var body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return body == null ? InputStream.nullInputStream() : body;
    }

    private static int timeoutMillis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }
}
