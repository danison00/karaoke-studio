# YouTube HLS Progressivo com Seek — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer vídeos adaptativos do YouTube começarem a tocar em HLS antes do download completo e permitir seek para trechos já cacheados ou para uma nova geração FFmpeg no ponto escolhido.

**Architecture:** Uma sessão UUID por reprodução possui gerações HLS independentes. O FFmpeg lê vídeo e áudio por dois relays HTTP internos validados, grava HLS fMP4 atomicamente e pode ser cancelado/reiniciado por seek. O Angular usa `hls.js` no mesmo `<video>` conectado ao AudioWorklet de pitch.

**Tech Stack:** Java 26, Spring Boot 4.1.1, JavaTube, FFmpeg 7+, Angular 22, TypeScript 6, `hls.js` estável mais recente, JUnit/Mockito/MockMvc, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-17-youtube-hls-progressive-seek-design.md`

## Global Constraints

- O alvo inicial é Chrome em celulares e vídeo adaptativo limitado a 360p.
- A primeira reprodução deve ser liberada antes de o download completo terminar; alvo p95 de `time_to_playable_ms` menor ou igual a 10 segundos no benchmark da especificação.
- A sessão deve manter `playbackGeneration` separado de `producerGeneration`.
- O último seek é definido por `requestSequence` do cliente; requisições antigas não podem cancelar a nova.
- HLS usa playlist EVENT, fMP4, segmentos alvo de 2 segundos e `temp_file`; arquivos incompletos nunca são servidos.
- FFmpeg recebe somente URLs de relay loopback com token interno; URLs assinadas do YouTube não podem aparecer no processo, API ou logs.
- O caminho progressivo existente continua sem FFmpeg/HLS.
- O mesmo `HTMLVideoElement` permanece conectado ao AudioWorklet; `playbackRate` e `defaultPlaybackRate` permanecem 1.
- A aplicação continua sendo um JAR Spring Boot que serve os arquivos estáticos do Angular.
- Não implementar escala horizontal, Safari/iOS, múltiplas qualidades, CDN ou persistência durável.
- Como o workspace não possui Git, usar os testes e o arquivo de plano como checkpoints; não criar commits artificiais.

## Review Focus

- Corrida entre seek antigo e novo: teste de `requestSequence` fora de ordem no serviço de sessão (Task 4).
- Reprodução de intervalo antigo enquanto uma continuação é produzida: teste de `playbackGeneration` versus `producerGeneration` e transição de fim de intervalo (Task 4).
- Falha de nova geração preservando conteúdo anterior: teste do estado `DEGRADED` e seek de recuperação (Task 4).
- Relay não expondo SSRF/URL assinada e suportando Range: testes de host, redirect, token e cancelamento (Task 3).
- Segmento/playlist parcial: teste de publicação atômica e allowlist de caminho HLS (Task 2).
- Browser mobile com HLS progressivo: teste Angular de attach/destroy, offset lógico e debounce (Task 5).

---

### Task 1: Dependência e modelos de HLS

**Files:**
- Modify: `package.json` — adicionar `hls.js` como dependência de runtime.
- Modify: `package-lock.json` — atualizar pelo npm, sem editar manualmente.
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegHlsProfile.java` — perfis COPY e TRANSCODE.
- Create: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegProcess.java` — handle cancelável do processo.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/PlaybackStatus.java` — estados RESOLVING, BUFFERING, STREAMING, READY, DEGRADED, FAILED, CANCELLED.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsRange.java` — intervalo lógico de uma geração.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsGenerationSnapshot.java` — snapshot imutável da geração.
- Modify: `src/main/java/com/karaokestudio/youtube/preparation/PreparationMode.java` — adicionar HLS e remover dependência conceitual de MUXED no novo fluxo.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsPreparationSnapshot.java` — snapshot público de sessão, incluindo playback/producer generation e ranges.
- Test: `src/test/java/com/karaokestudio/youtube/preparation/HlsRangeTest.java`.
- Test: `src/test/java/com/karaokestudio/youtube/preparation/HlsPreparationSnapshotTest.java`.

**Interfaces:**
- Produces `FfmpegHlsProfile`, `FfmpegProcess`, `HlsRange`, `HlsGenerationSnapshot`, `PlaybackStatus` e `HlsPreparationSnapshot` para Tasks 2–5.
- `FfmpegProcess` expõe `boolean isAlive()`, `void cancel()`, `CompletionStage<CommandResult> completion()` e `void close()` idempotente.
- `HlsRange` expõe `long fromMs()`, `long toMs()`, `long generation()`, `String playlistUrl()`.
- `HlsPreparationSnapshot` expõe `UUID id()`, `String videoId()`, `String title()`, `PlaybackStatus status()`, `PreparationMode mode()`, `long durationMs()`, `long playbackGeneration()`, `Long producerGeneration()`, `long acceptedSeekSequence()`, `long requestedPositionMs()`, `long timelineOffsetMs()`, `String playlistUrl()`, `List<HlsRange> availableRanges()`, `String mediaUrl()`, `String error()`, `String recoverableError()`.

- [ ] **Step 1: Write the failing Java tests** para normalização de ranges, rejeição de `toMs < fromMs` e snapshot imutável.
- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=HlsRangeTest,HlsPreparationSnapshotTest test`

Expected: FAIL porque os tipos novos ainda não existem.

- [ ] **Step 3: Implement the value types** com validação de limites e cópia defensiva das listas.
- [ ] **Step 4: Add `hls.js` and regenerate the lockfile**

Run: `npm install hls.js@latest --save`

Expected: `package.json` e `package-lock.json` contêm a versão instalada e o comando termina sem erro.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=HlsRangeTest,HlsPreparationSnapshotTest test`

Expected: PASS.

### Task 2: Runner FFmpeg cancelável, HLS e cache atômico

**Files:**
- Modify: `src/main/java/com/karaokestudio/youtube/ffmpeg/CommandExecutor.java` — adicionar operação assíncrona cancelável mantendo a operação síncrona de probe/mux legado enquanto necessária.
- Modify: `src/main/java/com/karaokestudio/youtube/ffmpeg/ProcessCommandExecutor.java` — iniciar processo, drenar stdout/stderr, cancelar graciosamente e forçar após 2 segundos.
- Modify: `src/main/java/com/karaokestudio/youtube/ffmpeg/FfmpegRunner.java` — adicionar `FfmpegProcess startHls(URI videoRelay, URI audioRelay, Path generationDirectory, FfmpegHlsProfile profile, long positionMs, Duration timeout)`.
- Modify: `src/main/java/com/karaokestudio/youtube/ffmpeg/ProcessFfmpegRunner.java` — montar lista de argumentos sem shell para HLS COPY/TRANSCODE.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsPlaylistReader.java` — ler somente playlists publicadas e somar `EXTINF`.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsMediaCache.java` — criar sessão/geração, publicar/abrir arquivos, listar ranges, finalizar playlist, excluir por TTL e validar paths.
- Test: `src/test/java/com/karaokestudio/youtube/ffmpeg/ProcessFfmpegRunnerTest.java` — comandos HLS e profile seek.
- Create: `src/test/java/com/karaokestudio/youtube/ffmpeg/ProcessCommandExecutorCancellationTest.java`.
- Create: `src/test/java/com/karaokestudio/youtube/preparation/HlsPlaylistReaderTest.java`.
- Create: `src/test/java/com/karaokestudio/youtube/preparation/HlsMediaCacheTest.java`.

**Interfaces:**
- Consumes `FfmpegHlsProfile` and relay URIs from Task 3/session service.
- `HlsMediaCache` produces `Path generationDirectory(UUID, long)`, `Optional<HlsGenerationSnapshot> inspect(...)`, `InputStream open(...)`, `void finalizeGeneration(...)`, `void discardSession(...)`, `void cleanup()`.
- `HlsPlaylistReader.inspect(Path playlist, long logicalOffsetMs, long generation)` returns a generation snapshot only if `index.m3u8` and all referenced files are complete and allowlisted.

- [ ] **Step 1: Write failing tests** para o comando HLS COPY, comando seek TRANSCODE com `-ss` antes dos inputs, cancelamento idempotente, soma de `EXTINF`, ocultação de `.tmp` e rejeição de traversal.
- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=ProcessFfmpegRunnerTest,ProcessCommandExecutorCancellationTest,HlsPlaylistReaderTest,HlsMediaCacheTest test`

Expected: FAIL nos novos comportamentos.

- [ ] **Step 3: Implement `FfmpegProcess` and asynchronous process execution** mantendo leitura limitada de stderr e encerramento gracioso/forçado.
- [ ] **Step 4: Implement HLS command construction** com `-nostdin`, `-f hls`, `-hls_time 2`, `-hls_list_size 0`, `-hls_playlist_type event`, `-hls_segment_type fmp4`, `temp_file+independent_segments`, COPY inicial e TRANSCODE com escala 360p/keyframes de 2 segundos.
- [ ] **Step 5: Implement playlist reader/cache** usando diretórios derivados de UUID e geração, rename atômico e allowlist de `index.m3u8`, `init.mp4` e `segment-*.m4s`.
- [ ] **Step 6: Run focused tests to verify they pass**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=ProcessFfmpegRunnerTest,ProcessCommandExecutorCancellationTest,HlsPlaylistReaderTest,HlsMediaCacheTest test`

Expected: PASS.

### Task 3: Relay HTTP interno validado

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/preparation/YoutubeRelayRegistry.java` — tokens, associação com geração e expiração/cancelamento.
- Create: `src/main/java/com/karaokestudio/web/YoutubeRelayController.java` — endpoints loopback de vídeo/áudio.
- Modify: `src/main/java/com/karaokestudio/youtube/HttpUpstreamStreamClient.java` or create adapter — expor stream validado para relay sem duplicar política de redirect.
- Modify: `src/main/java/com/karaokestudio/youtube/UpstreamStreamClient.java` if needed for relay response metadata.
- Create: `src/test/java/com/karaokestudio/youtube/preparation/YoutubeRelayRegistryTest.java`.
- Create: `src/test/java/com/karaokestudio/web/YoutubeRelayControllerTest.java`.

**Interfaces:**
- `YoutubeRelayRegistry.register(UUID sessionId, long generation, YoutubeStream video, YoutubeStream audio)` returns opaque `RelayHandles` with internal loopback URIs.
- `YoutubeRelayRegistry.open(String token, String mediaKind, String remoteAddress, String range)` returns a validated upstream response and increments a generation lease.
- `YoutubeRelayRegistry.cancel(UUID sessionId, long generation)` closes active upstream responses and invalidates tokens.
- Controller accepts only loopback source and token/path allowlisted; no browser-facing route is returned.

- [ ] **Step 1: Write failing tests** para token expirado, token de outra sessão, cliente remoto, Range validado, redirect não permitido e cancelamento fechando o upstream.
- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=YoutubeRelayRegistryTest,YoutubeRelayControllerTest test`

Expected: FAIL porque registry/controller ainda não existem.

- [ ] **Step 3: Implement registry** com `SecureRandom`/UUID token, associação de sessão/geração, leases e cancelamento idempotente.
- [ ] **Step 4: Implement controller/adapter** usando `UpstreamHostPolicy`, `HttpUpstreamStreamClient`, `HttpRange`, headers de conteúdo e `StreamingResponseBody` sem revelar URL assinada.
- [ ] **Step 5: Run focused tests**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=YoutubeRelayRegistryTest,YoutubeRelayControllerTest test`

Expected: PASS.

### Task 4: Serviço de sessões HLS e API Spring

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsPlaybackSession.java` — lock/estado, ranges, generations e sequence.
- Create: `src/main/java/com/karaokestudio/youtube/preparation/HlsPlaybackSessionService.java` — criar, resolver, iniciar geração, seek, snapshot, open HLS, delete, cleanup.
- Modify: `src/main/java/com/karaokestudio/youtube/preparation/YoutubePreparationService.java` — delegar adaptativo ao serviço HLS e manter progressivo.
- Modify: `src/main/java/com/karaokestudio/youtube/preparation/YoutubePreparationResponse.java` — serializar contrato novo.
- Modify: `src/main/java/com/karaokestudio/web/YoutubeMediaController.java` — endpoints POST/GET/POST seek/DELETE/HLS.
- Modify: `src/main/java/com/karaokestudio/web/ApiExceptionHandler.java` — códigos 409/416/502/504 novos.
- Modify: `src/main/resources/application.yml` — `max-height: 360`, `hls-segment-seconds: 2`, `hls-startup-segments: 2`, `hls-startup-fallback-seconds: 8`, `hls-idle-timeout: 2m` e limites de sessão.
- Test: `src/test/java/com/karaokestudio/youtube/preparation/HlsPlaybackSessionTest.java`.
- Test: `src/test/java/com/karaokestudio/youtube/preparation/HlsPlaybackSessionServiceTest.java`.
- Modify: `src/test/java/com/karaokestudio/youtube/preparation/YoutubePreparationServiceTest.java`.
- Modify: `src/test/java/com/karaokestudio/web/YoutubeMediaControllerTest.java`.

**Interfaces:**
- `HlsPlaybackSessionService.start(String rawUrl, String clientIp)` returns `HlsPreparationSnapshot` immediately em `RESOLVING`/`READY`.
- `HlsPlaybackSessionService.get(UUID id)` returns snapshot com `playbackGeneration`, `producerGeneration`, `availableRanges` e URLs same-origin.
- `HlsPlaybackSessionService.seek(UUID id, long positionMs, long requestSequence)` returns snapshot; sequence obsoleta é no-op.
- `HlsPlaybackSessionService.openHls(UUID id, long generation, String resourceName)` returns `HlsResource` com content type, length, body e close action.
- `HlsPlaybackSessionService.delete(UUID id)` é idempotente.

- [ ] **Step 1: Write failing tests** para criação imediata, streaming antes de ready, seek em cache, seek futuro, sequência fora de ordem, `DEGRADED`, continuação de intervalo, duas sessões independentes e limpeza.
- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=HlsPlaybackSessionTest,HlsPlaybackSessionServiceTest,YoutubePreparationServiceTest,YoutubeMediaControllerTest test`

Expected: FAIL nos estados/rotas HLS novos.

- [ ] **Step 3: Implement session state machine** com lock por sessão, `playbackGeneration` independente de `producerGeneration`, `requestSequence`, `DEGRADED` e erro recuperável.
- [ ] **Step 4: Implement initial resolution/streaming** com JavaTube, selector limitado a 360p, relay pair, monitor de playlist e liberação após dois segmentos ou fallback de um aos 8 segundos.
- [ ] **Step 5: Implement seek/cancel/continuation** com cancelamento da geração anterior, sobreposição de 2 segundos, seleção de range antigo e proteção contra publicação obsoleta.
- [ ] **Step 6: Implement controller and response mapping** para as rotas e content types HLS, sem quebrar o endpoint progressivo.
- [ ] **Step 7: Run focused tests**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" -Dskip.frontend=true -Dtest=HlsPlaybackSessionTest,HlsPlaybackSessionServiceTest,YoutubePreparationServiceTest,YoutubeMediaControllerTest test`

Expected: PASS.

### Task 5: Player Angular com hls.js, offset lógico e seek debounce

**Files:**
- Modify: `src/app/core/services/youtube-media.service.ts` — tipos HLS, polling até `STREAMING`, `seek`, `delete`, abort handling.
- Modify: `src/app/core/services/media-player.service.ts` — instância Hls, attach/destroy, geração, offset, ranges, seek lógico e continuação.
- Modify: `src/app/core/models/player-state.model.ts` — estados `resolving`, `streaming`, `degraded` e seek/buffering.
- Modify: `src/app/core/models/media-source.model.ts` — origem YouTube com preparation id, mode HLS e snapshot.
- Modify: `src/app/core/services/karaoke-store.service.ts` — mensagens e posição/duração lógica.
- Modify: `src/app/shared/components/media-player/media-player.ts` — debounce 400 ms, preview/commit e troca de playlist.
- Modify: `src/app/shared/components/media-player/media-player.html` — loading/seek UI e aria labels.
- Modify: `src/app/shared/components/media-player/media-player.scss` — estados de buffering/seek se necessário.
- Modify: `src/app/core/services/youtube-media.service.spec.ts`.
- Modify: `src/app/core/services/media-player.service.spec.ts`.
- Modify: `src/app/shared/components/media-player/media-player.spec.ts`.

**Interfaces:**
- `YoutubePreparation` aceita `RESOLVING | BUFFERING | STREAMING | READY | DEGRADED | FAILED | CANCELLED`, mode `PROGRESSIVE | HLS`, `playlistUrl`, `timelineOffsetMs`, ranges e generations.
- `YoutubeMediaService.prepare(url, signal)` resolve quando há media progressiva ou playlist HLS reproduzível.
- `YoutubeMediaService.seek(id, positionMs, requestSequence, signal)` retorna snapshot.
- `YoutubeMediaService.delete(id)` envia DELETE best-effort.
- `MediaPlayerService` mantém uma instância `Hls | null`, mas um único `HTMLMediaElement` e uma única conexão de áudio.

- [ ] **Step 1: Write failing Vitest tests** para polling em STREAMING, seek API com sequence, criação/destruição de Hls, offset lógico, ranges antigos e debounce.
- [ ] **Step 2: Run tests to verify they fail**

Run: `npx ng test --watch=false --include=src/app/core/services/youtube-media.service.spec.ts --include=src/app/core/services/media-player.service.spec.ts --include=src/app/shared/components/media-player/media-player.spec.ts`

Expected: FAIL por tipos/integração HLS ausentes.

- [ ] **Step 3: Implement service API** preservando progressive e liberando HLS em STREAMING, não READY.
- [ ] **Step 4: Implement MediaPlayerService HLS lifecycle** com `Hls.isSupported()`, `attachMedia`, `loadSource`, `stopLoad`, `destroy`, `startLoad`, `timelineOffsetMs` e resume state.
- [ ] **Step 5: Implement logical seek**: intervalo atual local, intervalo antigo via playlist, futuro via POST debounced, sequência monotônica e retomada no último alvo.
- [ ] **Step 6: Preserve pitch/audio graph** sem recriar `MediaElementAudioSourceNode` e sem mudar playback rate.
- [ ] **Step 7: Update template/store messages** para RESOLVING/BUFFERING/STREAMING/DEGRADED.
- [ ] **Step 8: Run focused tests**

Run: `npx ng test --watch=false --include=src/app/core/services/youtube-media.service.spec.ts --include=src/app/core/services/media-player.service.spec.ts --include=src/app/shared/components/media-player/media-player.spec.ts`

Expected: PASS.

### Task 6: Integração, configuração, documentação e verificação final

**Files:**
- Modify: `src/main/resources/application.yml` — conferir limites finais, `max-height: 360` e caminho FFmpeg.
- Modify: `docs/arquitetura-e-limitacoes.md` — documentar HLS, relay, seek e limitações móveis.
- Modify: `docs/roteiro-teste-manual.md` — adicionar roteiro Android Chrome para startup, seek futuro, retorno ao cache e pitch.
- Modify: `README.md` — incluir dependência FFmpeg/HLS e comandos locais/Render.
- Test: `src/test/java/com/karaokestudio/PackagedApplicationTest.java` e `StaticApplicationSmokeTest.java` — ajustar smoke para novas rotas/configuração.

**Interfaces:**
- Consumes all backend/frontend interfaces from Tasks 1–5.
- Produces a packaged JAR that contains Angular static assets and serves HLS endpoints from the same origin.

- [ ] **Step 1: Write failing integration assertions** para content types HLS, static Angular asset, startup config and no old adaptive MP4-only contract.
- [ ] **Step 2: Run the failing integration tests**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" test`

Expected: FAIL only where old MUXED/PREPARING assumptions conflict with the HLS contract.

- [ ] **Step 3: Update configuration and documentation** to match implemented values and local/Render FFmpeg setup.
- [ ] **Step 4: Run the complete Java suite**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" test`

Expected: all Java tests pass with zero failures.

- [ ] **Step 5: Run the complete Angular suite**

Run: `npm run test:ci`

Expected: all Angular tests pass with zero failures.

- [ ] **Step 6: Build the packaged application**

Run: `mvn -gs ".mvn\global-settings.xml" -s ".mvn\settings.xml" package`

Expected: Maven exits 0 and the JAR contains Angular assets under `BOOT-INF/classes/static`.

- [ ] **Step 7: Run local smoke test** with FFmpeg installed: start Spring Boot, create a preparation for a public adaptive video, observe `STREAMING` before `READY`, request playlist/first segment, perform a future seek and verify old generation remains available.

Expected: first playable HLS response arrives without full media file; no orphan `ffmpeg` remains after DELETE/shutdown.

