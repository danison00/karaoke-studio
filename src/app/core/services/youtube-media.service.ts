import { Injectable } from '@angular/core';

export type YoutubeMediaMode = 'PROGRESSIVE' | 'MUXED' | 'HLS';
export type YoutubePreparationStatus =
  | 'PREPARING'
  | 'RESOLVING'
  | 'BUFFERING'
  | 'STREAMING'
  | 'READY'
  | 'DEGRADED'
  | 'FAILED'
  | 'CANCELLED';

export interface YoutubeRange {
  generation: number;
  fromMs: number;
  toMs: number;
  playlistUrl: string;
}

export interface YoutubePreparation {
  id: string;
  videoId: string;
  title: string;
  status: YoutubePreparationStatus;
  mode: YoutubeMediaMode;
  contentType: string;
  durationMs: number;
  contentLength: number | null;
  mediaUrl: string | null;
  error: string | null;
  playbackGeneration?: number | null;
  producerGeneration?: number | null;
  acceptedSeekSequence?: number | null;
  requestedPositionMs?: number | null;
  timelineOffsetMs?: number | null;
  playlistUrl?: string | null;
  availableRanges?: YoutubeRange[];
  recoverableError?: string | null;
}

export type ReadyYoutubePreparation = YoutubePreparation & (
  | { mode: 'PROGRESSIVE'; status: 'READY'; mediaUrl: string }
  | { mode: 'HLS'; status: 'STREAMING' | 'READY' | 'DEGRADED'; playlistUrl: string }
  | { mode: 'MUXED'; status: 'READY'; mediaUrl: string }
);

interface ApiErrorPayload {
  message?: string;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class YoutubeMediaService {
  async prepare(url: string, signal?: AbortSignal): Promise<ReadyYoutubePreparation> {
    const initialResponse = await fetch('/api/youtube/preparations', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ url }),
      signal,
    });
    const initial = await this.readPreparationResponse(initialResponse);
    if (this.isPlayable(initial)) {
      return initial as ReadyYoutubePreparation;
    }

    let preparation = initial;
    const location = initialResponse.headers.get('Location')
      ?? `/api/youtube/preparations/${encodeURIComponent(initial.id)}`;
    while (!this.isPlayable(preparation) && !['FAILED', 'CANCELLED'].includes(preparation.status)) {
      await this.wait(this.retryDelay(initialResponse), signal);
      const response = await fetch(location, {
        headers: { Accept: 'application/json' },
        signal,
      });
      preparation = await this.readPreparationResponse(response);
    }

    if (this.isPlayable(preparation)) {
      return preparation as ReadyYoutubePreparation;
    }
    throw new Error(preparation.error ?? 'Não foi possível preparar o vídeo do YouTube.');
  }

  async seek(
    id: string,
    positionMs: number,
    requestSequence: number,
    signal?: AbortSignal,
  ): Promise<YoutubePreparation> {
    const response = await fetch(`/api/youtube/preparations/${encodeURIComponent(id)}/seek`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ positionMs, requestSequence }),
      signal,
    });
    return this.readPreparationResponse(response);
  }

  async delete(id: string): Promise<void> {
    try {
      await fetch(`/api/youtube/preparations/${encodeURIComponent(id)}`, { method: 'DELETE', keepalive: true });
    } catch {
      // Best effort: the server TTL also cleans up abandoned sessions.
    }
  }

  private isPlayable(preparation: YoutubePreparation): boolean {
    return (preparation.mode === 'HLS'
      && ['STREAMING', 'READY', 'DEGRADED'].includes(preparation.status)
      && !!preparation.playlistUrl)
      || (preparation.status === 'READY' && !!preparation.mediaUrl);
  }

  private async readPreparationResponse(response: Response): Promise<YoutubePreparation> {
    if (!response.ok) {
      const payload = await this.readError(response);
      throw new Error(payload?.message ?? payload?.error ?? `Não foi possível preparar o vídeo (HTTP ${response.status}).`);
    }
    return await response.json() as YoutubePreparation;
  }

  private retryDelay(response: Response): number {
    const retryAfter = Number(response.headers.get('Retry-After'));
    return Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 1000;
  }

  private wait(milliseconds: number, signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) {
      return Promise.reject(new DOMException('A preparação foi cancelada.', 'AbortError'));
    }
    return new Promise((resolve, reject) => {
      const timer = globalThis.setTimeout(() => {
        signal?.removeEventListener('abort', onAbort);
        resolve();
      }, milliseconds);
      const onAbort = () => {
        globalThis.clearTimeout(timer);
        signal?.removeEventListener('abort', onAbort);
        reject(new DOMException('A preparação foi cancelada.', 'AbortError'));
      };
      signal?.addEventListener('abort', onAbort, { once: true });
    });
  }

  private async readError(response: Response): Promise<ApiErrorPayload | null> {
    try {
      return await response.json() as ApiErrorPayload;
    } catch {
      return null;
    }
  }
}
