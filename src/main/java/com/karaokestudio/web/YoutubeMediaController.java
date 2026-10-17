package com.karaokestudio.web;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.karaokestudio.youtube.YoutubeMediaService;
import com.karaokestudio.youtube.preparation.PreparedMedia;
import com.karaokestudio.youtube.preparation.PreparationMode;
import com.karaokestudio.youtube.preparation.PreparationStatus;
import com.karaokestudio.youtube.preparation.YoutubePreparationService;
import com.karaokestudio.youtube.preparation.YoutubePreparationSnapshot;
import com.karaokestudio.youtube.preparation.HlsPlaybackSessionService;
import com.karaokestudio.youtube.preparation.HlsPreparationSnapshot;
import com.karaokestudio.youtube.preparation.MediaNotReadyException;
import com.karaokestudio.youtube.preparation.PlaybackStatus;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeMediaController {

    private static final Logger log = LoggerFactory.getLogger(YoutubeMediaController.class);

    private final YoutubePreparationService preparationService;
    private final YoutubeMediaService mediaService;
    private final CachedMediaStreamer cachedMediaStreamer;
    private final HlsPlaybackSessionService hlsSessionService;

    public YoutubeMediaController(
            YoutubePreparationService preparationService,
            YoutubeMediaService mediaService,
            CachedMediaStreamer cachedMediaStreamer) {
        this(preparationService, mediaService, cachedMediaStreamer, null);
    }

    @Autowired
    public YoutubeMediaController(
            YoutubePreparationService preparationService,
            YoutubeMediaService mediaService,
            CachedMediaStreamer cachedMediaStreamer,
            HlsPlaybackSessionService hlsSessionService) {
        this.preparationService = preparationService;
        this.mediaService = mediaService;
        this.cachedMediaStreamer = cachedMediaStreamer;
        this.hlsSessionService = hlsSessionService;
    }

    @PostMapping("/preparations")
    public ResponseEntity<YoutubePreparationResponse> prepare(
            @RequestBody PrepareYoutubeRequest request,
            HttpServletRequest servletRequest) {
        if (hlsSessionService != null) {
            var snapshot = hlsSessionService.start(request == null ? null : request.url(), servletRequest.getRemoteAddr());
            var response = YoutubePreparationResponse.from(snapshot);
            var location = URI.create("/api/youtube/preparations/" + snapshot.id());
            var builder = snapshot.status() == PlaybackStatus.READY || snapshot.status() == PlaybackStatus.STREAMING
                    ? ResponseEntity.ok()
                    : ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.RETRY_AFTER, "1");
            return builder.location(location).body(response);
        }
        var snapshot = preparationService.start(request == null ? null : request.url(), servletRequest.getRemoteAddr());
        var response = YoutubePreparationResponse.from(snapshot);
        var location = URI.create("/api/youtube/preparations/" + snapshot.id());
        var builder = snapshot.status() == PreparationStatus.READY
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.RETRY_AFTER, "1");
        return builder.location(location).body(response);
    }

    @GetMapping("/preparations/{id}")
    public YoutubePreparationResponse preparation(@PathVariable UUID id) {
        if (hlsSessionService != null) return YoutubePreparationResponse.from(hlsSessionService.get(id));
        return YoutubePreparationResponse.from(preparationService.get(id));
    }

    @PostMapping("/preparations/{id}/seek")
    public YoutubePreparationResponse seek(
            @PathVariable UUID id,
            @RequestBody SeekYoutubeRequest request) {
        if (hlsSessionService == null) throw new MediaNotReadyException("Seek HLS não está habilitado");
        var snapshot = hlsSessionService.seek(id,
                request == null ? -1 : request.positionMs(),
                request == null ? -1 : request.requestSequence());
        return YoutubePreparationResponse.from(snapshot);
    }

    @DeleteMapping("/preparations/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (hlsSessionService != null) hlsSessionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preparations/{id}/hls/{generation}/{resourceName}")
    public ResponseEntity<StreamingResponseBody> hls(
            @PathVariable UUID id,
            @PathVariable long generation,
            @PathVariable String resourceName) {
        if (hlsSessionService == null) throw new MediaNotReadyException("HLS não está habilitado");
        log.debug("youtube.hls.http.request sessionId={} generation={} resource={}", id, generation, resourceName);
        final HlsPlaybackSessionService.HlsResource resource;
        try {
            resource = hlsSessionService.openHls(id, generation, resourceName);
        } catch (RuntimeException exception) {
            log.warn("youtube.hls.http.unavailable sessionId={} generation={} resource={} exceptionType={} message={}",
                    id, generation, resourceName, exception.getClass().getName(), exception.getMessage());
            throw exception;
        }
        log.debug("youtube.hls.http.ready sessionId={} generation={} resource={} contentLength={}",
                id, generation, resourceName, resource.contentLength());
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, resource.contentType());
        headers.setContentLength(resource.contentLength());
        headers.setCacheControl("no-store");
        StreamingResponseBody body = output -> {
            try (resource) {
                resource.body().transferTo(output);
                output.flush();
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/preparations/{id}/media")
    public ResponseEntity<StreamingResponseBody> media(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletRequest servletRequest) {
        if (hlsSessionService != null) {
            var hls = hlsSessionService.get(id);
            if (hls.mode() == PreparationMode.PROGRESSIVE) {
                return stream(hlsSessionService.openProgressive(id, range, servletRequest.getRemoteAddr()));
            }
            throw new MediaNotReadyException("Mídia HLS deve ser aberta pela playlist");
        }
        var prepared = preparationService.open(id);
        YoutubeMediaService.MediaStream media = null;
        try {
            media = prepared.mode() == PreparationMode.MUXED
                    ? cachedMediaStreamer.open(prepared, range)
                    : mediaService.open(prepared, range, servletRequest.getRemoteAddr());
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, media.contentType());
            if (media.contentLength() >= 0) {
                headers.setContentLength(media.contentLength());
            }
            if (media.contentRange() != null && !media.contentRange().isBlank()) {
                headers.set(HttpHeaders.CONTENT_RANGE, media.contentRange());
            }
            if (media.acceptsRanges()) {
                headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            }
            if (media.etag() != null) {
                headers.setETag(media.etag());
            }
            headers.set("X-Karaoke-Media-Mode", media.mode().name());
            headers.setCacheControl("no-store");

            var openedMedia = media;
            StreamingResponseBody body = output -> {
                try (openedMedia) {
                    var buffer = new byte[16 * 1024];
                    int read;
                    while ((read = openedMedia.body().read(buffer)) >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                    output.flush();
                } catch (IOException exception) {
                    throw exception;
                }
            };
            return ResponseEntity.status(media.status()).headers(headers).body(body);
        } catch (RuntimeException exception) {
            if (media == null) {
                prepared.close();
            }
            throw exception;
        }
    }

    private ResponseEntity<StreamingResponseBody> stream(YoutubeMediaService.MediaStream media) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, media.contentType());
        if (media.contentLength() >= 0) headers.setContentLength(media.contentLength());
        if (media.contentRange() != null) headers.set(HttpHeaders.CONTENT_RANGE, media.contentRange());
        if (media.acceptsRanges()) headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set("X-Karaoke-Media-Mode", media.mode().name());
        headers.setCacheControl("no-store");
        StreamingResponseBody body = output -> {
            try (media) {
                media.body().transferTo(output);
                output.flush();
            }
        };
        return ResponseEntity.status(media.status()).headers(headers).body(body);
    }
}
