# YouTube FFmpeg Mux Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show visible video and audible sound for JavaTube-resolvable public YouTube videos by muxing separate adaptive streams with FFmpeg while preserving same-origin playback, seeking and half-semitone pitch control.

**Architecture:** Keep the current progressive stream proxy as a fast path. For adaptive media, create a deduplicated asynchronous preparation, securely download video and audio to bounded temporary files, run FFmpeg against local inputs, atomically publish a cached MP4, and serve it with HTTP Range support. Angular starts and polls the preparation before assigning the returned same-origin URL to the existing `<video>` element.

**Tech Stack:** Java 26, Spring Boot 4.1.1, JavaTube `master-f249f5fd61-1`, FFmpeg 7+, Angular 22.2, TypeScript 6, Node 26.12.0, npm 11.20.0, JUnit 5, Mockito, MockMvc, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-17-youtube-ffmpeg-mux-design.md`

## Global Constraints

- Preserve one deployable Spring Boot JAR containing the Angular static build; do not create a separately deployed frontend.
- Preserve `HTMLVideoElement -> AudioEngineService -> SoundTouch` and 0.5-semitone pitch increments.
- Keep JavaTube pinned at `master-f249f5fd61-1`; its `getAll()` and stream metadata API are sufficient.
- Keep direct progressive MP4 playback as the fast path; remove `AUDIO_ONLY` as a successful YouTube-video result.
- Use FFmpeg from `FFMPEG_PATH` or `PATH`; do not bundle a platform binary in the JAR.
- Invoke external processes only through argument-list `ProcessBuilder`; never use a shell or concatenate a command string.
- Never pass remote URLs to FFmpeg. Download through the validated Java HTTP path first, then give FFmpeg local paths.
- Never expose signed upstream URLs, local paths or raw FFmpeg stderr in an API response.
- Client polling cancellation does not cancel a shared server preparation.
- Preserve the existing SSRF policy and validate every upstream redirect.
- This directory is not currently a Git repository. Do not initialize Git; treat each passing task as a checkpoint and omit commit commands unless the user initializes Git first.

## Review Focus

- Two clients request the same video concurrently: exactly one preparation job runs and both receive the same preparation ID; covered in Task 4.
- A Google Video URL redirects to loopback/private/link-local space: both adaptive downloads fail before any private connection; covered in Task 3.
- FFmpeg exits nonzero, times out or is interrupted: process and partial files are removed, entry becomes `FAILED`, and executor capacity is released; covered in Tasks 2 and 4.
- Cached media receives closed, open-ended, suffix, multi-range and unsatisfiable Range headers: valid single ranges return correct `206`, unsupported/invalid ranges return `416`; covered in Task 5.
- Cleanup runs while a cached file is being served or prepared: active entries survive and only expired/LRU inactive entries are deleted; covered in Task 3.

---

### Task 1: Expose and select adaptive JavaTube streams

**Files:**
- Modify: `src/main/java/com/karaokestudio/youtube/YoutubeStream.java`
- Modify: `src/main/java/com/karaokestudio/youtube/JavaTubeStreamQuery.java`
- Create: `src/main/java/com/karaokestudio/youtube/AdaptiveStreamPair.java`
- Create: `src/main/java/com/karaokestudio/youtube/AdaptiveStreamSelector.java`
- Modify: `src/main/java/com/karaokestudio/youtube/ResolvedYoutubeMedia.java`
- Modify: `src/main/java/com/karaokestudio/youtube/JavaTubeResolver.java`
- Modify: `src/test/java/com/karaokestudio/youtube/JavaTubeResolverPocTest.java`
- Create: `src/test/java/com/karaokestudio/youtube/AdaptiveStreamSelectionTest.java`

**Interfaces:**
- Consumes: JavaTube `StreamQuery.getAll()` and `Stream` accessors inspected in the pinned JAR.
- Produces: `JavaTubeStreamQuery.all(): List<YoutubeStream>`; stream metadata accessors `includesVideo()`, `includesAudio()`, `videoCodec()`, `audioCodec()`, `resolution()`, `fps()`, `bitrate()` and `defaultAudioTrack()`; `AdaptiveStreamSelector.select(List<YoutubeStream>, int maxHeight): Optional<AdaptiveStreamPair>`; `ResolvedYoutubeMedia(videoId, title, mode, durationMs, videoStream, audioStream)` with `Mode.PROGRESSIVE | ADAPTIVE` and nullable `audioStream` only for `PROGRESSIVE`.

- [ ] **Step 1: Replace the audio-only fallback tests with failing adaptive-selection tests**

  Add assertions that:

  - a usable progressive MP4 still wins over adaptive streams;
  - without progressive media, the resolver returns `ADAPTIVE` with distinct video-only and audio-only streams;
  - H.264/MP4 video and AAC/M4A default audio are preferred over VP9/Opus alternatives;
  - the highest video at or below `max-height=1080` wins, then highest FPS;
  - the highest-bitrate default audio track wins;
  - SABR, OTF, missing URL and mixed/invalid tracks are rejected;
  - no usable pair raises `YoutubeResolutionException` containing `Nenhum par de vídeo e áudio compatível`.

- [ ] **Step 2: Run the focused Java tests and confirm the expected failure**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true -Dtest=JavaTubeResolverPocTest,AdaptiveStreamSelectionTest test`

  Expected: FAIL because `all()`, adaptive metadata and `Mode.ADAPTIVE` do not exist.

- [ ] **Step 3: Extend the local JavaTube adapters**

  Implement the interfaces above. Adapt `StreamQuery.getAll()`, `includeVideoTrack()`, `includeAudioTrack()`, codecs, resolution, FPS, bitrate and default-audio flags. Keep JavaTube classes inside `JavaTubeResolver`'s adapter boundary.

- [ ] **Step 4: Implement deterministic adaptive ranking**

  Implement `AdaptiveStreamSelector` as a focused class. Parse heights from values such as `1080p`; streams with missing/unparseable height rank below known safe heights. Return `PROGRESSIVE` first, otherwise a complete `ADAPTIVE` pair. Do not return successful audio-only media.

- [ ] **Step 5: Run the focused tests**

  Run the Task 1 command again.

  Expected: PASS with tests proving progressive precedence, adaptive pairing and rejection cases.

### Task 2: Add FFmpeg probing and safe process execution

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/CommandExecutor.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/ProcessCommandExecutor.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/CommandResult.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegCapabilities.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegProbe.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegRunner.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/MuxProfile.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/ProcessFfmpegRunner.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegUnavailableException.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegExecutionException.java`
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegTimeoutException.java`
- Create: `src/test/java/com/karaokestudio/youtube/ffmpeg/ProcessCommandExecutorTest.java`
- Create: `src/test/java/com/karaokestudio/youtube/ffmpeg/ProcessFfmpegRunnerTest.java`

**Interfaces:**
- Consumes: local input/output `Path` objects and `ResolvedYoutubeMedia` codec metadata from Task 1.
- Produces: `FfmpegProbe.probe(): FfmpegCapabilities`; `FfmpegRunner.mux(Path video, Path audio, Path outputPart, MuxProfile profile, Duration timeout): void`; `MuxProfile.COPY | TRANSCODE`.

- [ ] **Step 1: Write failing tests for command execution**

  Test that `ProcessCommandExecutor` drains stdout/stderr concurrently, caps captured diagnostics at `64 KiB`, returns the exit code, kills a process after timeout, preserves thread interruption and never invokes `cmd`, PowerShell, `sh` or `bash`.

- [ ] **Step 2: Write failing FFmpeg command tests**

  With a fake `CommandExecutor`, assert exact argument-list properties:

  - common flags include `-nostdin`, `-hide_banner`, `-loglevel error`, `-y`, two local `-i` values and explicit maps;
  - `COPY` includes `-c:v copy -c:a copy`;
  - `TRANSCODE` includes `-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 160k`;
  - both include `-movflags +faststart -f mp4` and the supplied output path;
  - nonzero exit, timeout and unavailable executable map to their stable exception types without including signed URLs.

- [ ] **Step 3: Run tests and confirm failure**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true -Dtest=ProcessCommandExecutorTest,ProcessFfmpegRunnerTest test`

  Expected: FAIL because the process/FFmpeg package is absent.

- [ ] **Step 4: Implement process execution, probing and FFmpeg runner**

  `FfmpegProbe` runs `<executable> -version` and `<executable> -encoders` once, returning availability plus `libx264`/AAC capability. `ProcessFfmpegRunner` chooses no paths or profiles itself; it only builds the exact command for its parameters and delegates process lifetime to `CommandExecutor`.

- [ ] **Step 5: Run the focused tests**

  Expected: PASS without requiring a real FFmpeg installation.

### Task 3: Secure downloads and bounded temporary cache

**Files:**
- Modify: `src/main/java/com/karaokestudio/youtube/UpstreamStreamClient.java`
- Modify: `src/main/java/com/karaokestudio/youtube/HttpUpstreamStreamClient.java`
- Modify: `src/test/java/com/karaokestudio/youtube/UpstreamStreamClientTest.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/SecureStreamDownloader.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/YoutubeMediaCache.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/CachedMedia.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/MediaTooLargeException.java`
- Create: `src/test/java/com/karaokestudio/youtube/preparation/SecureStreamDownloaderTest.java`
- Create: `src/test/java/com/karaokestudio/youtube/preparation/YoutubeMediaCacheTest.java`

**Interfaces:**
- Consumes: `YoutubeStream` from Task 1 and `UpstreamHostPolicy`.
- Produces: `UpstreamStreamClient.open(YoutubeStream stream, String rangeHeader)`; `SecureStreamDownloader.download(YoutubeStream, Path, long maxBytes): long`; cache allocation/publish/acquire/release/cleanup operations under one configured root.

- [ ] **Step 1: Generalize the upstream-client tests**

  Change the existing tests to open a `YoutubeStream` directly. Add a redirect-chain test where an initially allowed host redirects to `127.0.0.1`, expecting `UpstreamHostPolicyException` before the redirected connection is opened.

- [ ] **Step 2: Write failing downloader tests**

  Assert atomic local writes, exact byte count, deletion on interrupted/failed copy, and `MediaTooLargeException` when the stream exceeds the configured maximum even if `Content-Length` was absent or false.

- [ ] **Step 3: Write failing cache tests**

  Use JUnit `@TempDir` and assert:

  - all resolved paths remain under the configured root and use only video ID/UUID-derived names;
  - publishing uses `.part` then atomic/fallback-safe move;
  - startup cleanup removes abandoned input/partial files;
  - TTL and size-budget cleanup evict only inactive entries in least-recently-used order;
  - an acquired/active cached file is not deleted until released.

- [ ] **Step 4: Run the focused tests and confirm failure**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true -Dtest=UpstreamStreamClientTest,SecureStreamDownloaderTest,YoutubeMediaCacheTest test`

- [ ] **Step 5: Implement the generalized client, downloader and cache**

  Keep manual redirect handling in `HttpUpstreamStreamClient`. The downloader streams bytes and counts them; it does not load media into memory. `YoutubeMediaCache` owns all file creation/deletion and refuses path escape or symlink traversal outside its resolved root.

- [ ] **Step 6: Run the focused tests**

  Expected: PASS, including redirect SSRF and active-file eviction protection.

### Task 4: Coordinate deduplicated asynchronous preparations

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/preparation/PreparationStatus.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/PreparationMode.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/YoutubePreparation.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/YoutubePreparationSnapshot.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/PreparedMedia.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/YoutubePreparationService.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/MediaPreparationException.java`
- Create: `src/main/java/com/karaokestudio/youtube/preparation/NoCompatibleStreamsException.java`
- Create: `src/test/java/com/karaokestudio/youtube/preparation/YoutubePreparationServiceTest.java`

**Interfaces:**
- Consumes: resolver from Task 1, runner/probe from Task 2, downloader/cache from Task 3 and existing request limits.
- Produces: `start(String rawUrl, String clientIp): YoutubePreparationSnapshot`; `get(UUID id): YoutubePreparationSnapshot`; `open(UUID id): PreparedMedia`; `cleanup(): void`. On a cache miss, `start` owns one existing `RequestLimiter.Lease` until resolution/preparation ends; deduplicated followers acquire no extra lease.

- [ ] **Step 1: Write failing lifecycle tests**

  Cover `PROGRESSIVE -> READY` without FFmpeg, `ADAPTIVE -> PREPARING -> READY/MUXED`, sanitized `FAILED`, unknown/expired ID, duration limit, unavailable encoder, download failure, nonzero FFmpeg, timeout and application-shutdown interruption.

- [ ] **Step 2: Write the concurrency and deduplication tests**

  Use latches/fake futures to prove that two simultaneous starts for one video ID return the same UUID and call downloader/FFmpeg once; different IDs use separate jobs; the ninth queued job is rejected when two workers plus eight queue slots are occupied; released slots accept later jobs.

- [ ] **Step 3: Run the focused test and confirm failure**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true -Dtest=YoutubePreparationServiceTest test`

- [ ] **Step 4: Implement preparation state and orchestration**

  Key deduplication by validated video ID. Publish immutable snapshots. For adaptive jobs, allocate cache paths, download video then audio through `SecureStreamDownloader`, choose `COPY` only for H.264/AAC MP4-compatible pairs, invoke FFmpeg, publish atomically and clean inputs in `finally`. Angular aborts do not reach this service.

- [ ] **Step 5: Add bounded executor and scheduled cleanup wiring**

  The service receives an explicitly configured executor with two workers and queue capacity eight. Rejection becomes `RequestLimitException`. Schedule cache/preparation cleanup; on bean destruction interrupt active jobs, terminate FFmpeg through the command executor and clean partials.

- [ ] **Step 6: Run the focused tests**

  Expected: PASS with no real network or FFmpeg dependency.

### Task 5: Replace raw-URL endpoints with preparation and ranged-media APIs

**Files:**
- Modify: `src/main/java/com/karaokestudio/web/YoutubeMediaController.java`
- Replace: `src/main/java/com/karaokestudio/web/YoutubeMetadata.java`
- Create: `src/main/java/com/karaokestudio/web/PrepareYoutubeRequest.java`
- Create: `src/main/java/com/karaokestudio/web/YoutubePreparationResponse.java`
- Create: `src/main/java/com/karaokestudio/web/CachedMediaStreamer.java`
- Modify: `src/main/java/com/karaokestudio/web/ApiExceptionHandler.java`
- Modify: `src/main/java/com/karaokestudio/youtube/YoutubeMediaService.java`
- Modify: `src/test/java/com/karaokestudio/web/YoutubeMediaControllerTest.java`
- Create: `src/test/java/com/karaokestudio/web/CachedMediaStreamerTest.java`

**Interfaces:**
- Consumes: preparation snapshots/media handles from Task 4 and existing `HttpRange` parsing.
- Produces: `POST /api/youtube/preparations`; `GET /api/youtube/preparations/{id}`; `GET /api/youtube/preparations/{id}/media`.

- [ ] **Step 1: Rewrite controller tests around the new contract**

  Assert request-body validation, `200` for already-ready reuse, `202 + Location + Retry-After` for preparation, status polling JSON without signed URLs/paths, `404` expired ID, `409` before ready and every error mapping from the design.

- [ ] **Step 2: Write cached-file Range tests**

  Against a small `@TempDir` file, assert:

  - no Range returns `200` and the full file;
  - `bytes=10-19`, `bytes=10-` and `bytes=-10` return exact bytes and headers;
  - ranges beyond EOF, malformed ranges and multi-ranges return `416` with `Content-Range: bytes */<total>`;
  - acquired cache handles are released after normal completion and client disconnect.

- [ ] **Step 3: Run the focused tests and confirm failure**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true -Dtest=YoutubeMediaControllerTest,CachedMediaStreamerTest test`

- [ ] **Step 4: Implement the preparation controller and DTO mapping**

  Return only the design fields. Emit `mediaUrl` only for `READY`. Add stable exception mappings for `NO_COMPATIBLE_STREAMS`, `MEDIA_TOO_LARGE`, `FFMPEG_UNAVAILABLE`, `PREPARATION_TIMEOUT` and `MEDIA_NOT_READY`.

- [ ] **Step 5: Implement direct and cached media serving**

  Keep the current validated upstream proxy for `PROGRESSIVE`. Use `CachedMediaStreamer` for `MUXED`, with one supported byte range, exact headers, `ETag`, no-store and streaming from disk without reading the entire file.

- [ ] **Step 6: Remove the old raw-URL endpoints after tests use the new routes**

  Delete `/metadata?url=` and `/media?url=` mappings only after Task 5 tests demonstrate the replacement. Keep the SPA/static mappings unchanged.

- [ ] **Step 7: Run focused and full Java tests**

  Run: `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true test`

  Expected: all Java tests pass; no test requires installed FFmpeg or internet.

### Task 6: Migrate Angular to preparation polling and visible muxed video

**Files:**
- Modify: `src/app/core/services/youtube-media.service.ts`
- Modify: `src/app/core/services/youtube-media.service.spec.ts`
- Modify: `src/app/core/services/media-player.service.ts`
- Modify: `src/app/core/services/media-player.service.spec.ts`
- Modify: `src/app/core/models/media-source.model.ts`
- Modify: `src/app/core/models/player-state.model.ts`
- Modify: `src/app/core/services/karaoke-store.service.ts`
- Modify: `src/app/core/services/karaoke-store.service.spec.ts`
- Modify: `src/app/shared/components/media-player/media-player.html`
- Modify: `src/app/shared/components/media-player/media-player.spec.ts`
- Modify: `src/app/shared/components/status-bar/status-bar.html`
- Modify: `src/app/shared/components/status-bar/status-bar.spec.ts`

**Interfaces:**
- Consumes: Task 5 preparation JSON and `mediaUrl`.
- Produces: `YoutubeMediaService.prepare(url: string, signal?: AbortSignal): Promise<ReadyYoutubePreparation>`; source mode `PROGRESSIVE | MUXED`; player status `preparing`.

- [ ] **Step 1: Write failing API polling tests**

  Mock `fetch` and fake timers to assert one POST, one-second polling while `PREPARING`, resolution on `READY`, immediate error on `FAILED`, URL use from `mediaUrl`, `Retry-After` handling, and clean `AbortError` behavior without a server cancellation request.

- [ ] **Step 2: Write failing player/store/template tests**

  Assert that loading YouTube enters `preparing`, shows “Preparando vídeo do YouTube…”, aborts prior polling when the source changes, sets the returned same-origin URL, makes the `<video>` visible for `PROGRESSIVE` and `MUXED`, removes the YouTube audio-only cover, attaches the audio engine and leaves pitch enabled.

- [ ] **Step 3: Run focused Angular tests and confirm failure**

  Run after `nvm use 26.12.0`:

  `npx ng test --watch=false --include=src/app/core/services/youtube-media.service.spec.ts --include=src/app/core/services/media-player.service.spec.ts --include=src/app/core/services/karaoke-store.service.spec.ts --include=src/app/shared/components/media-player/media-player.spec.ts --include=src/app/shared/components/status-bar/status-bar.spec.ts`

- [ ] **Step 4: Implement the preparation client and cancellation ownership**

  `YoutubeMediaService` owns polling only for the provided signal. `MediaPlayerService` owns one `AbortController`, aborts it in `teardownCurrent()`, awaits a ready preparation, sets `element.src = mediaUrl`, attaches `AudioEngineService`, then calls `load()`.

- [ ] **Step 5: Update models, status copy and player visibility**

  Replace `AUDIO_ONLY` with `MUXED`, add `preparing`, keep local-audio cover behavior, and use video-oriented YouTube messages (“Vídeo YouTube pronto”, “Reproduzindo vídeo YouTube”). Do not change pitch ranges or steps.

- [ ] **Step 6: Run focused tests, full Angular tests and production build**

  Run:

  - `npm run test:ci`
  - `npm run build`

  Expected: all Angular tests pass and `dist/karaoke-studio/browser` is generated.

### Task 7: Bind configuration, document FFmpeg operations and verify the packaged app

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/YoutubeMediaProperties.java`
- Modify: `src/main/java/com/karaokestudio/web/YoutubeMediaConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/karaokestudio/youtube/YoutubeMediaPropertiesTest.java`
- Modify: `src/test/java/com/karaokestudio/PackagedApplicationTest.java`
- Create: `src/test/java/com/karaokestudio/youtube/ffmpeg/RealFfmpegSmokeTest.java`
- Modify: `README.md`
- Modify: `docs/arquitetura-e-limitacoes.md`
- Modify: `docs/roteiro-teste-manual.md`
- Modify: `docs/superpowers/notes/javatube-poc.md`

**Interfaces:**
- Consumes: all Task 1–6 components.
- Produces: typed `@ConfigurationProperties(prefix = "app.youtube")`, environment overrides and final operator instructions.

- [ ] **Step 1: Write failing property-binding tests**

  Bind and assert defaults from the design: executable `ffmpeg`, height `1080`, duration `2h`, input `2GB`, timeout `15m`, ready TTL `2h`, failed TTL `10m`, cache `5GB`, workers `2` and queue `8`. Assert invalid nonpositive sizes/durations/concurrency fail startup validation.

- [ ] **Step 2: Replace scattered `@Value` parameters with typed properties**

  Preserve all existing URL, timeout and request-limit values. Add `FFMPEG_PATH` and `YOUTUBE_CACHE_DIR` environment overrides in `application.yml`; do not embed an OS-specific path.

- [ ] **Step 3: Add an opt-in real FFmpeg smoke test**

  `RealFfmpegSmokeTest` runs only when `FFMPEG_INTEGRATION_TEST=true`. Generate tiny local color/video and sine/audio fixtures with FFmpeg, mux them through `ProcessFfmpegRunner`, and assert a nonempty playable MP4 via `ffprobe` or FFmpeg decode. Its absence must skip, not fail, the normal suite.

- [ ] **Step 4: Update documentation**

  Document FFmpeg 7+ with `libx264` and AAC, Windows/Linux installation expectations, `FFMPEG_PATH`, cache sizing/cleanup, first-play preparation delay, unsupported private/DRM/SABR/OTF media, resource limits, API examples and the YouTube rights/terms caveat. Update the manual roteiro to test visible video, seeking, fullscreen, half-semitone pitch and repeated-cache playback.

- [ ] **Step 5: Run complete verification**

  With Java 26 and Node 26.12.0:

  - `npm run test:ci`
  - `npm run build`
  - `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true test`
  - `mvn -gs .mvn/global-settings.xml -s .mvn/settings.xml -Dskip.frontend=true clean package`
  - `jar tf target/karaoke-studio-0.1.0-SNAPSHOT.jar`

  Expected: Angular and Java suites pass; the JAR contains Angular `index.html`, worklet, Java application classes and all preparation/FFmpeg classes.

- [ ] **Step 6: Perform the real acceptance test when FFmpeg is available**

  Start the packaged JAR, submit `https://www.youtube.com/watch?v=dQw4w9WgXcQ`, poll to `READY/MUXED`, confirm `/media` returns `video/mp4` and a valid `206` range, then verify in the browser that video is visible, audio plays, seeking works and `+0.5`/`-0.5` pitch remains synchronized.

  If FFmpeg is not installed in the implementation environment, do not claim this final acceptance passed. Report the exact missing prerequisite while still reporting unit/build results separately.

## Final Self-Review Checklist

- Every design requirement maps to a task: adaptive selection (1), process safety (2), secure download/cache (3), lifecycle/deduplication (4), API/range (5), Angular (6), operations/acceptance (7).
- All cross-task names are consistent: `PROGRESSIVE | ADAPTIVE` internally; `PROGRESSIVE | MUXED` publicly; `PREPARING | READY | FAILED` for lifecycle.
- No task requires remote network or real FFmpeg for its normal automated tests.
- The real acceptance test is explicitly conditional because FFmpeg is not currently present on this workstation.
- No step initializes Git or deletes files outside the configured cache root.
