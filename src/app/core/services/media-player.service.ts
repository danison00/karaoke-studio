import { Injectable, inject } from '@angular/core';
import Hls from 'hls.js';
import { LocalMediaKind, LocalMediaSource } from '../models/media-source.model';
import { AudioEngineService } from './audio-engine.service';
import { KaraokeStore } from './karaoke-store.service';
import { YoutubeMediaService, YoutubeRange } from './youtube-media.service';

@Injectable({ providedIn: 'root' })
export class MediaPlayerService {
  private readonly audioEngine = inject(AudioEngineService);
  private readonly store = inject(KaraokeStore);
  private readonly youtubeMedia = inject(YoutubeMediaService);

  private element: HTMLMediaElement | null = null;
  private objectUrl: string | null = null;
  private removeListeners: (() => void) | null = null;
  private youtubeAbortController: AbortController | null = null;
  private youtubeSeekAbortController: AbortController | null = null;
  private youtubePreparationId: string | null = null;
  private youtubeMode: 'PROGRESSIVE' | 'MUXED' | 'HLS' | null = null;
  private youtubeTimelineOffsetSeconds = 0;
  private youtubeDurationSeconds = 0;
  private youtubeRanges: YoutubeRange[] = [];
  private youtubeSeekSequence = 0;
  private youtubeSeekTimer: ReturnType<typeof setTimeout> | null = null;
  private hls: Hls | null = null;
  private nativeHls = false;

  async loadLocal(file: File, element: HTMLMediaElement): Promise<void> {
    this.teardownCurrent();
    this.store.selectSource('local');
    this.store.patchPlayer({ isLoading: true, status: 'loading', mediaName: file.name });

    this.element = element;
    this.objectUrl = URL.createObjectURL(file);
    const source: LocalMediaSource = {
      type: 'local',
      file,
      objectUrl: this.objectUrl,
      kind: this.resolveKind(file),
    };
    this.store.setSource(source);
    this.bindEvents(element);

    element.src = this.objectUrl;
    element.preload = 'metadata';
    element.playbackRate = 1;
    element.defaultPlaybackRate = 1;
    element.preservesPitch = false;

    try {
      const pitchAvailable = await this.audioEngine.attach(element);
      this.store.patchPlayer({ canProcessPitch: pitchAvailable });
      element.load();
    } catch {
      this.store.patchPlayer({
        isLoading: false,
        status: 'error',
        canProcessPitch: false,
        errorMessage: 'Não foi possível iniciar o processamento de áudio. Interaja com a página e tente novamente.',
      });
      throw new Error('AudioContext initialization failed');
    }
  }

  async loadYoutube(url: string, element: HTMLMediaElement): Promise<void> {
    this.teardownCurrent();
    this.store.selectSource('youtube');
    this.store.patchPlayer({
      isLoading: true,
      status: 'preparing',
      mediaName: url,
      errorMessage: null,
    });

    this.element = element;
    this.bindEvents(element);
    const controller = new AbortController();
    this.youtubeAbortController = controller;

    try {
      const preparation = await this.youtubeMedia.prepare(url, controller.signal);
      if (controller.signal.aborted || this.youtubeAbortController !== controller) {
        return;
      }
      this.store.setSource({
        type: 'youtube',
        videoId: preparation.videoId,
        url,
        title: preparation.title,
        mode: preparation.mode,
        preparationId: preparation.id,
      });
      this.youtubePreparationId = preparation.id;
      this.youtubeMode = preparation.mode;
      this.youtubeTimelineOffsetSeconds = (preparation.timelineOffsetMs ?? 0) / 1000;
      this.youtubeDurationSeconds = preparation.durationMs / 1000;
      this.youtubeRanges = preparation.availableRanges ?? [];
      this.store.patchPlayer({
        mediaName: preparation.title,
        duration: this.youtubeDurationSeconds,
        canProcessPitch: true,
      });

      element.preload = 'metadata';
      element.playbackRate = 1;
      element.defaultPlaybackRate = 1;
      element.preservesPitch = false;
      const pitchAvailable = await this.audioEngine.attach(element);
      this.store.patchPlayer({ canProcessPitch: pitchAvailable });
      if (preparation.mode === 'HLS') {
        if (!preparation.playlistUrl) throw new Error('A playlist HLS não foi disponibilizada.');
        this.attachHls(preparation.playlistUrl, element);
      } else {
        if (!preparation.mediaUrl) throw new Error('A mídia do YouTube não foi disponibilizada.');
        element.src = preparation.mediaUrl;
        element.load();
      }
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      this.store.patchPlayer({
        isLoading: false,
        status: 'error',
        canProcessPitch: false,
        errorMessage: error instanceof Error ? error.message : 'Não foi possível carregar o vídeo do YouTube.',
      });
      throw error;
    }
  }

  async play(): Promise<void> {
    if (!this.element) return;
    const element = this.element;
    try {
      // Call play() directly from the click task. Mobile browsers can reject
      // playback when an awaited AudioContext.resume() runs first.
      element.playbackRate = 1;
      const playback = element.play();
      void this.audioEngine.resume().catch(() => undefined);
      await playback;
      this.store.patchPlayer({
        isPlaying: true,
        status: this.store.settings().pitchSemitones === 0 ? 'playing' : 'processing',
        errorMessage: null,
      });
    } catch {
      this.store.patchPlayer({
        status: 'error',
        errorMessage: 'O navegador bloqueou a reprodução. Clique em reproduzir novamente.',
      });
    }
  }

  pause(): void {
    if (!this.element) return;
    this.element.pause();
    this.store.patchPlayer({ isPlaying: false, status: 'paused' });
  }

  restart(): void {
    if (!this.element) return;
    this.element.currentTime = 0;
  }

  seek(seconds: number): void {
    if (!this.element) return;
    const target = Math.max(0, Math.min(this.youtubeDurationSeconds || this.element.duration || 0, seconds));
    if (this.youtubeMode === 'HLS' && this.youtubePreparationId) {
      const localTarget = target - this.youtubeTimelineOffsetSeconds;
      const localDuration = Number.isFinite(this.element.duration) ? this.element.duration : 0;
      if (localTarget >= 0 && localDuration > 0 && localTarget <= localDuration) {
        this.element.currentTime = localTarget;
        this.store.patchPlayer({ currentTime: target });
        return;
      }
      this.store.patchPlayer({ currentTime: target, status: 'seeking', isBuffering: true });
      if (this.youtubeSeekTimer) globalThis.clearTimeout(this.youtubeSeekTimer);
      const sequence = ++this.youtubeSeekSequence;
      this.youtubeSeekTimer = globalThis.setTimeout(() => void this.performYoutubeSeek(target, sequence), 400);
      return;
    }
    const duration = Number.isFinite(this.element.duration) ? this.element.duration : 0;
    this.element.currentTime = Math.max(0, Math.min(duration, seconds));
  }

  dispose(): void {
    this.teardownCurrent();
    this.store.setSource(null);
    this.store.patchPlayer({
      isPlaying: false,
      isLoading: false,
      isBuffering: false,
      currentTime: 0,
      duration: 0,
      canProcessPitch: false,
      status: 'empty',
      mediaName: null,
    });
  }

  private bindEvents(element: HTMLMediaElement): void {
    const listeners: Array<[keyof HTMLMediaElementEventMap, EventListener]> = [
      ['loadedmetadata', () => this.store.patchPlayer({
        duration: this.youtubeMode === 'HLS'
          ? this.youtubeDurationSeconds
          : Number.isFinite(element.duration) ? element.duration : 0,
        isLoading: false,
        status: this.youtubeMode === 'HLS' ? 'streaming' : 'ready',
        errorMessage: null,
      })],
      ['durationchange', () => this.store.patchPlayer({
        duration: this.youtubeMode === 'HLS'
          ? this.youtubeDurationSeconds
          : Number.isFinite(element.duration) ? element.duration : 0,
      })],
      ['timeupdate', () => this.store.patchPlayer({ currentTime: this.logicalCurrentTime(element) })],
      ['play', () => this.store.patchPlayer({ isPlaying: true, status: 'playing' })],
      ['playing', () => this.store.patchPlayer({
        isPlaying: true,
        isBuffering: false,
        status: this.store.settings().pitchSemitones === 0 ? 'playing' : 'processing',
      })],
      ['pause', () => this.store.patchPlayer({ isPlaying: false, status: 'paused' })],
      ['waiting', () => this.store.patchPlayer({ isBuffering: true, status: 'buffering' })],
      ['seeking', () => this.store.patchPlayer({ status: 'loading' })],
      ['seeked', () => this.store.patchPlayer({
        currentTime: this.logicalCurrentTime(element),
        status: element.paused ? 'paused' : 'playing',
      })],
      ['ended', () => this.store.patchPlayer({
        isPlaying: false,
        currentTime: element.duration,
        status: 'ended',
      })],
      ['error', () => this.store.patchPlayer({
        isPlaying: false,
        isLoading: false,
        status: 'error',
        errorMessage: this.store.player().sourceType === 'youtube'
          ? 'Não foi possível reproduzir a mídia do YouTube.'
          : 'Não foi possível reproduzir este arquivo. Verifique o formato e o codec.',
      })],
    ];

    for (const [type, listener] of listeners) {
      element.addEventListener(type, listener);
    }
    this.removeListeners = () => {
      for (const [type, listener] of listeners) {
        element.removeEventListener(type, listener);
      }
    };
  }

  private teardownCurrent(): void {
    this.youtubeAbortController?.abort();
    this.youtubeAbortController = null;
    this.youtubeSeekAbortController?.abort();
    this.youtubeSeekAbortController = null;
    if (this.youtubeSeekTimer) globalThis.clearTimeout(this.youtubeSeekTimer);
    this.youtubeSeekTimer = null;
    if (this.youtubePreparationId) void this.youtubeMedia.delete?.(this.youtubePreparationId);
    this.youtubePreparationId = null;
    this.youtubeMode = null;
    this.youtubeRanges = [];
    this.youtubeTimelineOffsetSeconds = 0;
    this.youtubeDurationSeconds = 0;
    this.youtubeSeekSequence = 0;
    this.hls?.destroy();
    this.hls = null;
    this.nativeHls = false;
    this.removeListeners?.();
    this.removeListeners = null;
    if (this.element) {
      this.element.pause();
      this.element.removeAttribute('src');
      this.element.load();
      this.audioEngine.disposeSource();
    }
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
    }
    this.element = null;
    this.objectUrl = null;
  }

  private attachHls(playlistUrl: string, element: HTMLMediaElement): void {
    if (element.canPlayType('application/vnd.apple.mpegurl')) {
      this.nativeHls = true;
      element.src = playlistUrl;
      element.load();
      return;
    }
    if (!Hls.isSupported()) {
      throw new Error('Este navegador não suporta reprodução HLS neste dispositivo.');
    }
    const hls = new Hls({ enableWorker: true, lowLatencyMode: false });
    this.hls = hls;
    hls.on(Hls.Events.MEDIA_ATTACHED, () => {
      hls.loadSource(playlistUrl);
      hls.startLoad(0);
    });
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data.fatal) {
        this.store.patchPlayer({ isLoading: false, isBuffering: false, status: 'error',
          errorMessage: 'Não foi possível reproduzir o stream HLS.' });
      }
    });
    hls.attachMedia(element);
  }

  private async performYoutubeSeek(targetSeconds: number, sequence: number): Promise<void> {
    if (!this.youtubePreparationId || !this.element || this.youtubeMode !== 'HLS') return;
    this.youtubeSeekAbortController?.abort();
    const controller = new AbortController();
    this.youtubeSeekAbortController = controller;
    try {
      const preparation = await this.youtubeMedia.seek(
        this.youtubePreparationId, Math.round(targetSeconds * 1000), sequence, controller.signal);
      if (controller.signal.aborted || sequence !== this.youtubeSeekSequence || preparation.mode !== 'HLS'
        || !preparation.playlistUrl) return;
      this.youtubeTimelineOffsetSeconds = (preparation.timelineOffsetMs ?? targetSeconds * 1000) / 1000;
      this.youtubeRanges = preparation.availableRanges ?? this.youtubeRanges;
      this.youtubeDurationSeconds = preparation.durationMs / 1000;
      if (this.nativeHls && this.element) {
        this.element.src = preparation.playlistUrl;
        this.element.load();
      } else {
        this.hls?.stopLoad();
        this.hls?.loadSource(preparation.playlistUrl);
        this.hls?.startLoad(0);
      }
      this.store.patchPlayer({
        currentTime: targetSeconds,
        duration: this.youtubeDurationSeconds,
        isBuffering: true,
        isLoading: false,
        status: preparation.status === 'DEGRADED' ? 'degraded' : 'streaming',
      });
    } catch (error) {
      if (!controller.signal.aborted && sequence === this.youtubeSeekSequence) {
        this.store.patchPlayer({ isBuffering: false, status: 'error',
          errorMessage: error instanceof Error ? error.message : 'Não foi possível avançar no vídeo.' });
      }
    }
  }

  private logicalCurrentTime(element: HTMLMediaElement): number {
    return this.youtubeMode === 'HLS'
      ? Math.min(this.youtubeDurationSeconds, this.youtubeTimelineOffsetSeconds + element.currentTime)
      : element.currentTime;
  }

  private resolveKind(file: File): LocalMediaKind {
    const extension = file.name.split('.').pop()?.toLowerCase();
    return file.type.startsWith('video/') || extension === 'mp4' || extension === 'webm'
      ? 'video'
      : 'audio';
  }
}
