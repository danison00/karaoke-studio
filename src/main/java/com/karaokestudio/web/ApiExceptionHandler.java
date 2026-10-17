package com.karaokestudio.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.karaokestudio.youtube.RangeNotSatisfiableException;
import com.karaokestudio.youtube.UpstreamHostPolicyException;
import com.karaokestudio.youtube.UpstreamMediaException;
import com.karaokestudio.youtube.YoutubeResolutionException;
import com.karaokestudio.youtube.YoutubeUrlException;
import com.karaokestudio.youtube.ffmpeg.FfmpegTimeoutException;
import com.karaokestudio.youtube.ffmpeg.FfmpegUnavailableException;
import com.karaokestudio.youtube.preparation.MediaNotReadyException;
import com.karaokestudio.youtube.preparation.MediaPreparationException;
import com.karaokestudio.youtube.preparation.MediaTooLargeException;
import com.karaokestudio.youtube.preparation.NoCompatibleStreamsException;
import com.karaokestudio.youtube.preparation.YoutubeRelayException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(YoutubeUrlException.class)
    ResponseEntity<ApiError> invalidUrl(YoutubeUrlException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_YOUTUBE_URL", exception.getMessage());
    }

    @ExceptionHandler(YoutubeResolutionException.class)
    ResponseEntity<ApiError> resolutionFailure(YoutubeResolutionException exception) {
        return error(HttpStatus.NOT_FOUND, "YOUTUBE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MediaPreparationException.class)
    ResponseEntity<ApiError> preparationNotFound(MediaPreparationException exception) {
        return error(HttpStatus.NOT_FOUND, "PREPARATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MediaNotReadyException.class)
    ResponseEntity<ApiError> mediaNotReady(MediaNotReadyException exception) {
        return error(HttpStatus.CONFLICT, "MEDIA_NOT_READY", exception.getMessage());
    }

    @ExceptionHandler(NoCompatibleStreamsException.class)
    ResponseEntity<ApiError> incompatibleStreams(NoCompatibleStreamsException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "NO_COMPATIBLE_STREAMS", exception.getMessage());
    }

    @ExceptionHandler(MediaTooLargeException.class)
    ResponseEntity<ApiError> mediaTooLarge(MediaTooLargeException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(FfmpegUnavailableException.class)
    ResponseEntity<ApiError> ffmpegUnavailable(FfmpegUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "FFMPEG_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(FfmpegTimeoutException.class)
    ResponseEntity<ApiError> ffmpegTimeout(FfmpegTimeoutException exception) {
        return error(HttpStatus.GATEWAY_TIMEOUT, "PREPARATION_TIMEOUT", exception.getMessage());
    }

    @ExceptionHandler(RangeNotSatisfiableException.class)
    ResponseEntity<ApiError> invalidRange(RangeNotSatisfiableException exception) {
        var response = error(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "INVALID_RANGE", exception.getMessage());
        if (exception.totalLength() == null) {
            return response;
        }
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.totalLength())
                .body(response.getBody());
    }

    @ExceptionHandler(RequestLimitException.class)
    ResponseEntity<ApiError> rateLimit(RequestLimitException exception) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", exception.getMessage());
    }

    @ExceptionHandler({UpstreamHostPolicyException.class, UpstreamMediaException.class})
    ResponseEntity<ApiError> upstreamFailure(RuntimeException exception) {
        return error(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILURE", exception.getMessage());
    }

    @ExceptionHandler(YoutubeRelayException.class)
    ResponseEntity<ApiError> relayFailure(YoutubeRelayException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_RELAY", exception.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
