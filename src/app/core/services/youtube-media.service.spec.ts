import { beforeEach, describe, expect, it, vi } from 'vitest';
import { YoutubeMediaService } from './youtube-media.service';

describe('YoutubeMediaService', () => {
  let service: YoutubeMediaService;
  const fetchMock = vi.fn();
  const url = 'https://youtu.be/dQw4w9WgXcQ';

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    service = new YoutubeMediaService();
  });

  it('starts a ready progressive preparation and returns its same-origin media URL', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      id: 'prep-1',
      videoId: 'dQw4w9WgXcQ',
      title: 'Vídeo público',
      status: 'READY',
      mode: 'PROGRESSIVE',
      contentType: 'video/mp4',
      durationMs: 123_000,
      contentLength: 456_000,
      mediaUrl: '/api/youtube/preparations/prep-1/media',
      error: null,
    }), { status: 200 }));

    await expect(service.prepare(url)).resolves.toMatchObject({
      videoId: 'dQw4w9WgXcQ',
      mode: 'PROGRESSIVE',
      mediaUrl: '/api/youtube/preparations/prep-1/media',
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/youtube/preparations', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ url }),
    }));
  });

  it('polls a preparing muxed video using the retry interval until ready', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 'prep-2', videoId: 'dQw4w9WgXcQ', title: 'Vídeo', status: 'PREPARING', mode: 'MUXED',
        contentType: 'video/mp4', durationMs: 123_000, contentLength: null, mediaUrl: null, error: null,
      }), { status: 202, headers: { Location: '/api/youtube/preparations/prep-2', 'Retry-After': '1' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 'prep-2', videoId: 'dQw4w9WgXcQ', title: 'Vídeo', status: 'READY', mode: 'MUXED',
        contentType: 'video/mp4', durationMs: 123_000, contentLength: 999, mediaUrl: '/api/youtube/preparations/prep-2/media', error: null,
      }), { status: 200 }));

    const promise = service.prepare(url);
    await Promise.resolve();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1_000);

    await expect(promise).resolves.toMatchObject({ mode: 'MUXED', mediaUrl: '/api/youtube/preparations/prep-2/media' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it('surfaces a failed preparation message', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      id: 'prep-3', videoId: 'dQw4w9WgXcQ', title: 'Vídeo', status: 'FAILED', mode: 'MUXED',
      contentType: 'video/mp4', durationMs: 123_000, contentLength: null, mediaUrl: null,
      error: 'FFmpeg não está disponível para preparar este vídeo.',
    }), { status: 200 }));

    await expect(service.prepare(url)).rejects.toThrow('FFmpeg não está disponível');
  });

  it('releases an HLS preparation as soon as the playlist is streaming', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      id: 'prep-hls', videoId: 'dQw4w9WgXcQ', title: 'Vídeo HLS', status: 'STREAMING', mode: 'HLS',
      contentType: 'application/vnd.apple.mpegurl', durationMs: 123_000, contentLength: null,
      mediaUrl: null, playlistUrl: '/api/youtube/preparations/prep-hls/hls/0/index.m3u8',
      timelineOffsetMs: 0, availableRanges: [], error: null, recoverableError: null,
    }), { status: 200 }));

    await expect(service.prepare(url)).resolves.toMatchObject({
      id: 'prep-hls', mode: 'HLS', status: 'STREAMING',
      playlistUrl: '/api/youtube/preparations/prep-hls/hls/0/index.m3u8',
    });
  });

  it('sends a monotonic seek request to the HLS session', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      id: 'prep-hls', videoId: 'dQw4w9WgXcQ', title: 'Vídeo HLS', status: 'BUFFERING', mode: 'HLS',
      contentType: 'application/vnd.apple.mpegurl', durationMs: 123_000, contentLength: null,
      mediaUrl: null, playlistUrl: '/api/youtube/preparations/prep-hls/hls/1/index.m3u8',
      timelineOffsetMs: 60_000, availableRanges: [], error: null, recoverableError: null,
    }), { status: 200 }));

    await service.seek('prep-hls', 60_000, 3);

    expect(fetchMock).toHaveBeenCalledWith('/api/youtube/preparations/prep-hls/seek', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ positionMs: 60_000, requestSequence: 3 }),
    }));
  });

  it('aborts polling without sending a cancellation request', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      id: 'prep-4', videoId: 'dQw4w9WgXcQ', title: 'Vídeo', status: 'PREPARING', mode: 'MUXED',
      contentType: 'video/mp4', durationMs: 123_000, contentLength: null, mediaUrl: null, error: null,
    }), { status: 202 }));

    const promise = service.prepare(url, controller.signal);
    await Promise.resolve();
    controller.abort();
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});
