import { TestBed } from '@angular/core/testing';
import Hls from 'hls.js';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AudioEngineService } from './audio-engine.service';
import { KaraokeStore } from './karaoke-store.service';
import { MediaPlayerService } from './media-player.service';
import { YoutubeMediaService, YoutubePreparation } from './youtube-media.service';

describe('MediaPlayerService', () => {
  const audioEngine = {
    attach: vi.fn(async () => true),
    resume: vi.fn(async () => undefined),
    disposeSource: vi.fn(),
  };
  const youtubeMedia = {
    prepare: vi.fn(async (): Promise<YoutubePreparation> => ({
      id: 'prep-1',
      videoId: 'dQw4w9WgXcQ',
      title: 'Vídeo público',
      status: 'READY' as const,
      mode: 'MUXED' as const,
      contentType: 'video/mp4',
      durationMs: 120_000,
      contentLength: 500_000,
      mediaUrl: '/api/youtube/preparations/prep-1/media',
      error: null,
    })),
    seek: vi.fn(async (): Promise<YoutubePreparation> => ({
      id: 'prep-hls',
      videoId: 'dQw4w9WgXcQ',
      title: 'Vídeo público',
      status: 'STREAMING',
      mode: 'HLS',
      contentType: 'application/vnd.apple.mpegurl',
      durationMs: 120_000,
      contentLength: null,
      mediaUrl: null,
      playlistUrl: '/api/youtube/preparations/prep-hls/hls/1/index.m3u8',
      timelineOffsetMs: 60_000,
      error: null,
    })),
  };
  let service: MediaPlayerService;
  let store: KaraokeStore;
  let element: HTMLAudioElement;

  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:karaoke');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        MediaPlayerService,
        KaraokeStore,
        { provide: AudioEngineService, useValue: audioEngine },
        { provide: YoutubeMediaService, useValue: youtubeMedia },
      ],
    });
    service = TestBed.inject(MediaPlayerService);
    store = TestBed.inject(KaraokeStore);
    element = document.createElement('audio');
    vi.spyOn(element, 'play').mockResolvedValue();
    vi.spyOn(element, 'pause').mockImplementation(() => undefined);
    vi.spyOn(element, 'load').mockImplementation(() => undefined);
  });

  it('loads a local file through an object URL without uploading it', async () => {
    const file = new File(['audio'], 'ensaio.mp3', { type: 'audio/mpeg' });

    await service.loadLocal(file, element);

    expect(URL.createObjectURL).toHaveBeenCalledWith(file);
    expect(element.src).toContain('blob:karaoke');
    expect(element.playbackRate).toBe(1);
    expect(audioEngine.attach).toHaveBeenCalledWith(element);
    expect(store.source()?.type).toBe('local');
  });

  it('starts native playback without waiting for the audio context resume', async () => {
    await service.loadLocal(new File(['audio'], 'ensaio.mp3', { type: 'audio/mpeg' }), element);

    let resolveResume!: () => void;
    audioEngine.resume.mockImplementationOnce(
      () => new Promise<undefined>((resolve) => (resolveResume = () => resolve(undefined))),
    );

    const playback = service.play();
    await Promise.resolve();

    expect(element.play).toHaveBeenCalledOnce();

    resolveResume();
    await playback;
  });

  it('keeps the playback state synchronized when mobile media events are delayed', async () => {
    await service.loadLocal(new File(['audio'], 'ensaio.mp3', { type: 'audio/mpeg' }), element);

    await service.play();

    expect(store.player().isPlaying).toBe(true);

    service.pause();

    expect(store.player().isPlaying).toBe(false);
  });

  it('mirrors native media timing and playback events into the store', async () => {
    await service.loadLocal(new File(['audio'], 'ensaio.wav', { type: 'audio/wav' }), element);
    Object.defineProperty(element, 'duration', { configurable: true, value: 180 });
    Object.defineProperty(element, 'currentTime', { configurable: true, writable: true, value: 27 });

    element.dispatchEvent(new Event('loadedmetadata'));
    element.dispatchEvent(new Event('play'));
    element.dispatchEvent(new Event('timeupdate'));

    expect(store.player().duration).toBe(180);
    expect(store.player().currentTime).toBe(27);
    expect(store.player().isPlaying).toBe(true);
  });

  it('revokes the previous object URL and removes listeners when disposed', async () => {
    await service.loadLocal(new File(['video'], 'karaoke.mp4', { type: 'video/mp4' }), element);

    service.dispose();
    element.dispatchEvent(new Event('play'));

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:karaoke');
    expect(audioEngine.disposeSource).toHaveBeenCalledOnce();
    expect(store.player().isPlaying).toBe(false);
  });

  it('loads YouTube through the same media element and attaches pitch processing', async () => {
    const url = 'https://youtu.be/dQw4w9WgXcQ';

    await service.loadYoutube(url, element);

    expect(youtubeMedia.prepare).toHaveBeenCalledWith(url, expect.any(AbortSignal));
    expect(element.src).toContain('/api/youtube/preparations/prep-1/media');
    expect(audioEngine.attach).toHaveBeenCalledWith(element);
    expect(store.source()).toMatchObject({ type: 'youtube', mode: 'MUXED' });
    expect(store.player().canProcessPitch).toBe(true);
  });

  it('keeps YouTube playback available when pitch processing is unavailable', async () => {
    audioEngine.attach.mockResolvedValueOnce(false);

    await service.loadYoutube('https://youtu.be/dQw4w9WgXcQ', element);

    expect(element.src).toContain('/api/youtube/preparations/prep-1/media');
    expect(store.player().canProcessPitch).toBe(false);
    expect(store.player().status).not.toBe('error');
  });

  it('uses native HLS playback when the browser does not expose Media Source Extensions', async () => {
    const hlsPreparation: YoutubePreparation = {
      id: 'prep-hls',
      videoId: 'dQw4w9WgXcQ',
      title: 'Vídeo público',
      status: 'STREAMING',
      mode: 'HLS',
      contentType: 'application/vnd.apple.mpegurl',
      durationMs: 120_000,
      contentLength: null,
      mediaUrl: null,
      playlistUrl: '/api/youtube/preparations/prep-hls/hls/0/index.m3u8',
      error: null,
    };
    youtubeMedia.prepare.mockResolvedValueOnce(hlsPreparation);
    vi.spyOn(Hls, 'isSupported').mockReturnValue(false);
    vi.spyOn(element, 'canPlayType').mockReturnValue('maybe');

    await service.loadYoutube('https://youtu.be/dQw4w9WgXcQ', element);

    expect(element.src).toContain('/api/youtube/preparations/prep-hls/hls/0/index.m3u8');
    expect(element.load).toHaveBeenCalled();
    expect(store.source()).toMatchObject({ type: 'youtube', mode: 'HLS' });
  });

  it('reloads the native HLS playlist when seeking to a new server generation', async () => {
    const hlsPreparation: YoutubePreparation = {
      id: 'prep-hls',
      videoId: 'dQw4w9WgXcQ',
      title: 'Vídeo público',
      status: 'STREAMING',
      mode: 'HLS',
      contentType: 'application/vnd.apple.mpegurl',
      durationMs: 120_000,
      contentLength: null,
      mediaUrl: null,
      playlistUrl: '/api/youtube/preparations/prep-hls/hls/0/index.m3u8',
      error: null,
    };
    youtubeMedia.prepare.mockResolvedValueOnce(hlsPreparation);
    vi.spyOn(Hls, 'isSupported').mockReturnValue(false);
    vi.spyOn(element, 'canPlayType').mockReturnValue('maybe');

    vi.useFakeTimers();
    try {
      await service.loadYoutube('https://youtu.be/dQw4w9WgXcQ', element);
      service.seek(60);
      await vi.advanceTimersByTimeAsync(400);
    } finally {
      vi.useRealTimers();
    }

    expect(youtubeMedia.seek).toHaveBeenCalledWith(
      'prep-hls', 60_000, 1, expect.any(AbortSignal));
    expect(element.src).toContain('/api/youtube/preparations/prep-hls/hls/1/index.m3u8');
    expect(element.load).toHaveBeenCalledTimes(2);
  });
});
