package com.karaokestudio.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.karaokestudio.youtube.UpstreamStreamClient;
import com.karaokestudio.youtube.preparation.YoutubeRelayRegistry;

@RestController
@RequestMapping("/api/internal/youtube-relay")
public class YoutubeRelayController {

    private final YoutubeRelayRegistry registry;

    public YoutubeRelayController(YoutubeRelayRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/{token}/{mediaKind}")
    public ResponseEntity<StreamingResponseBody> relay(
            @PathVariable String token,
            @PathVariable String mediaKind,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletRequest request) {
        var upstream = registry.open(token, mediaKind, request.getRemoteAddr(), range);
        var headers = new HttpHeaders();
        if (upstream.contentType() != null && !upstream.contentType().isBlank()) {
            headers.set(HttpHeaders.CONTENT_TYPE, upstream.contentType());
        }
        if (upstream.contentLength() >= 0) headers.setContentLength(upstream.contentLength());
        if (upstream.contentRange() != null) headers.set(HttpHeaders.CONTENT_RANGE, upstream.contentRange());
        if (upstream.acceptsRanges()) headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("no-store");
        StreamingResponseBody body = output -> {
            try (upstream) {
                var buffer = new byte[16 * 1024];
                int read;
                while ((read = upstream.body().read(buffer)) >= 0) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.flush();
            } catch (IOException exception) {
                throw exception;
            }
        };
        return ResponseEntity.status(upstream.status()).headers(headers).body(body);
    }
}
