# YouTube Adaptive Streams with FFmpeg — Design

## Intent

The current backend resolves a public YouTube URL through JavaTube and serves a direct progressive MP4 when one is available. For many current videos, YouTube exposes separate adaptive video and audio streams instead. The resolver falls back to `AUDIO_ONLY`, and the Angular player deliberately hides the video surface.

This change must preserve the single Spring Boot deployment, same-origin playback, seeking, fullscreen and browser-side half-semitone pitch processing while showing video for public YouTube videos that JavaTube can resolve.

## Goals

- Keep the existing direct progressive path as the low-latency fast path.
- When only adaptive streams are available, select one video stream and one audio stream, download both through the existing validated server-side network boundary, and combine them with FFmpeg.
- Serve the completed file from Spring Boot with byte-range support so the native `<video>` element can seek normally.
- Deduplicate concurrent requests for the same video and reuse a bounded temporary cache.
- Keep signed Google Video URLs, filesystem paths and FFmpeg diagnostics out of API responses.
- Preserve the Angular `HTMLVideoElement -> AudioEngineService -> SoundTouch` pipeline, including 0.5-semitone pitch increments.

## Non-goals

- No YouTube iframe fallback.
- No permanent media library or user-visible download feature.
- No attempt to bypass private, age-gated, paid, DRM-protected, region-blocked or authenticated content.
- No support for SABR/OTF-only media until JavaTube can expose a safely downloadable stream.
- No exact preparation percentage; the UI shows an indeterminate preparation state.
- No bundled FFmpeg executable inside the application JAR.

## Architecture

### Resolution and stream selection

`JavaTubeResolver` continues to prefer a direct progressive `video/mp4` stream. If none is usable, it inspects all JavaTube streams and returns an adaptive pair instead of an audio-only fallback.

The pinned JavaTube build already provides `StreamQuery.getAll()` plus `Stream` accessors for direct URL, codecs, MIME type, resolution, FPS, bitrate and presence of audio/video tracks. The local `JavaTubeStreamQuery` and `YoutubeStream` adapters are extended to expose those values; replacing JavaTube is not part of this change.

The adaptive selector accepts only streams with a direct HTTPS URL, no SABR flag and no OTF flag. It chooses a video-only stream at or below the configured maximum height, preferring MP4/H.264 and then the highest resolution and frame rate. It chooses a default audio-only stream, preferring M4A/AAC and then the highest bitrate. If the selected pair is H.264 plus AAC, FFmpeg uses stream copy; otherwise FFmpeg transcodes to H.264/AAC for browser-compatible MP4 output.

The resolver result has two modes:

- `PROGRESSIVE`: one stream containing video and audio; served by the existing proxy path.
- `ADAPTIVE`: one video-only stream and one audio-only stream; requires preparation before playback.

The public API reports an adaptive result as `MUXED` only after FFmpeg has produced the playable MP4. `AUDIO_ONLY` is removed from the normal video flow.

### Preparation lifecycle

The browser starts playback preparation with `POST /api/youtube/preparations` and body `{ "url": "..." }`. The server validates the URL, applies request limits, resolves JavaTube metadata and creates or reuses an in-memory preparation entry keyed by video ID.

For a progressive result, the entry becomes `READY` immediately and refers to the resolved direct stream. For an adaptive result, a bounded executor performs these steps:

1. Validate both signed upstream URLs through `UpstreamHostPolicy`.
2. Download the video and audio streams into private temporary files using the Java HTTP client, retaining manual redirect validation and enforcing configured byte limits.
3. Run FFmpeg through `ProcessBuilder` with local input paths only. Shell command construction is forbidden.
4. Write to an output `.part` file and atomically rename it to the final `.mp4` only after FFmpeg exits successfully.
5. Delete input and partial files in a `finally` block.
6. Mark the preparation `READY`, or retain a sanitized failure code and message as `FAILED`.

Only one preparation may run per video ID. Concurrent requests receive the same opaque UUID and observe the same future. The executor defaults to two active jobs and eight queued jobs; a full queue returns `429`.

Aborting Angular polling or switching to another video does not cancel the shared server job. The job remains reusable by other clients and runs until success, its configured timeout, application shutdown or an internal failure. “Cancellation cleanup” refers to server-owned interruption during timeout or shutdown; there is no client cancellation endpoint in this scope.

### API contract

`POST /api/youtube/preparations`

- Returns `200` when an existing entry is already ready, otherwise `202`.
- Sets `Location` to `/api/youtube/preparations/{id}`.
- Sets `Retry-After: 1` while preparation is incomplete.

`GET /api/youtube/preparations/{id}` returns:

```json
{
  "id": "opaque-uuid",
  "videoId": "dQw4w9WgXcQ",
  "title": "Video title",
  "status": "PREPARING",
  "mode": "MUXED",
  "contentType": "video/mp4",
  "durationMs": 213000,
  "contentLength": null,
  "mediaUrl": null,
  "error": null
}
```

Statuses are `PREPARING`, `READY` and `FAILED`. When ready, `mediaUrl` is `/api/youtube/preparations/{id}/media` and `contentLength` is known. A missing or expired UUID returns `404`.

`GET /api/youtube/preparations/{id}/media`:

- For `PROGRESSIVE`, proxies the captured direct stream with the current redirect and host validation.
- For `MUXED`, serves the cached file with `200`/`206`, `Content-Type: video/mp4`, `Content-Length`, `Content-Range`, `Accept-Ranges: bytes`, `ETag` and `Cache-Control: no-store`.
- Returns `409 MEDIA_NOT_READY` if requested before completion.

The old `/api/youtube/metadata?url=...` and `/api/youtube/media?url=...` endpoints are removed after Angular migrates, so the raw URL is no longer repeated during playback.

### FFmpeg execution

FFmpeg is an external runtime dependency configured by `FFMPEG_PATH`, defaulting to `ffmpeg` on `PATH`. Application startup probes `ffmpeg -version` once and logs availability. Missing FFmpeg does not prevent local-file playback or progressive YouTube playback; an adaptive preparation fails with `503 FFMPEG_UNAVAILABLE`.

Copy-compatible pairs use:

```text
ffmpeg -nostdin -hide_banner -loglevel error -y
  -i <video-file> -i <audio-file>
  -map 0:v:0 -map 1:a:0
  -c:v copy -c:a copy
  -movflags +faststart -f mp4 <output.part>
```

Other direct adaptive pairs use `libx264` with preset `veryfast`, CRF `23`, pixel format `yuv420p`, and AAC at `160k`. The process has a configurable 15-minute timeout. Standard error is drained concurrently and retained only in a bounded internal diagnostic buffer. Cancellation and timeout terminate the child process forcibly and delete partial files.

### Cache and resource limits

The cache directory defaults to `${java.io.tmpdir}/karaoke-studio-youtube` and can be overridden with `YOUTUBE_CACHE_DIR`. Files are derived only from validated video IDs and server-generated UUIDs.

Defaults:

- maximum selected height: `1080`;
- maximum video duration: `2h`;
- maximum bytes per downloaded input: `2GB`;
- preparation timeout: `15m`;
- ready-entry TTL: `2h` since last access;
- failed-entry TTL: `10m`;
- total cache budget: `5GB`;
- active preparations: `2`;
- queued preparations: `8`.

A scheduled cleanup removes expired entries and least-recently-used ready files until the budget is respected. Startup removes abandoned `.part`, `.video` and `.audio` files only from the configured cache directory. Active files are never deleted.

### Angular behavior

`YoutubeMediaService` posts the URL, polls the preparation resource every second while it is `PREPARING`, and resolves only when `READY`. An `AbortSignal` cancels polling when the user changes source or leaves the page.

`MediaPlayerService` keeps the player in an explicit `preparing` state, displays “Preparando vídeo do YouTube…”, and assigns the returned same-origin `mediaUrl` to the existing `<video>` element. Once metadata loads, the existing audio engine attaches and pitch remains available. The video surface is visible for both `PROGRESSIVE` and `MUXED`; the audio-only cover is removed from the YouTube path.

### Error behavior

- Invalid URL: `400 INVALID_YOUTUBE_URL`.
- Private, unavailable or unresolvable video: `404 YOUTUBE_NOT_FOUND`.
- No usable direct video/audio pair: `422 NO_COMPATIBLE_STREAMS`.
- Duration or byte limit exceeded: `413 MEDIA_TOO_LARGE`.
- Preparation queue full or IP limit exceeded: `429 RATE_LIMITED`.
- FFmpeg missing or required encoder unavailable: `503 FFMPEG_UNAVAILABLE`.
- FFmpeg timeout: `504 PREPARATION_TIMEOUT`.
- Upstream rejection or failed download: `502 UPSTREAM_FAILURE`.
- Preparation requested before ready: `409 MEDIA_NOT_READY`.

Failures exposed to clients are sanitized. Full JavaTube/FFmpeg details stay in server logs with preparation ID and video ID, never signed URLs.

## Deployment and operational constraints

The deployable artifact remains one Spring Boot JAR containing the Angular static build. The target server must separately install FFmpeg and expose it on `PATH` or through `FFMPEG_PATH`. FFmpeg 7 or newer with H.264 (`libx264`) and AAC encoders is the supported baseline.

Public accessibility does not imply permission to copy or redistribute a video. The application must document that operators and users remain responsible for complying with YouTube terms and applicable rights.

## Acceptance criteria

- The public video used during diagnosis (`dQw4w9WgXcQ`) resolves as adaptive, reaches `READY`, and plays with visible video and audible sound.
- Seeking produces valid byte-range responses from the muxed file.
- Pitch adjustments in 0.5-semitone increments continue to affect the audio while video remains synchronized.
- Two simultaneous preparations for the same video launch one download/mux job.
- Missing FFmpeg yields a clear adaptive-video error without breaking local files or progressive YouTube videos.
- Temporary files are removed after success, failure, timeout and cancellation.
- Java, Angular and packaged-JAR test suites pass.
