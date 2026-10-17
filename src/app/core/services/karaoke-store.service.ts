import { Injectable, computed, signal } from '@angular/core';
import {
  AudioSettings,
  DEFAULT_AUDIO_SETTINGS,
} from '../models/audio-settings.model';
import { MediaSource, MediaSourceType } from '../models/media-source.model';
import { DEFAULT_PLAYER_STATE, PlayerState } from '../models/player-state.model';

@Injectable({ providedIn: 'root' })
export class KaraokeStore {
  private readonly settingsState = signal<AudioSettings>({ ...DEFAULT_AUDIO_SETTINGS });
  private readonly playerState = signal<PlayerState>({ ...DEFAULT_PLAYER_STATE });
  private readonly sourceState = signal<MediaSource | null>(null);

  readonly settings = this.settingsState.asReadonly();
  readonly player = this.playerState.asReadonly();
  readonly source = this.sourceState.asReadonly();

  readonly statusMessage = computed(() => {
    const player = this.playerState();

    if (player.errorMessage) return player.errorMessage;
    if (player.isLoading) {
      return player.status === 'preparing' ? 'Preparando vídeo do YouTube…'
        : player.sourceType === 'youtube' ? 'Carregando vídeo do YouTube…' : 'Carregando mídia…';
    }
    if (player.isBuffering) return 'Aguardando dados de reprodução…';
    if (player.sourceType === 'youtube') {
      if (player.canProcessPitch && this.settingsState().pitchSemitones !== 0) return 'Processando áudio YouTube';
      if (player.isPlaying) return 'Reproduzindo vídeo YouTube';
      return 'Vídeo YouTube pronto';
    }
    if (!player.sourceType) return 'Selecione uma música para começar';
    if (player.canProcessPitch && this.settingsState().pitchSemitones !== 0) {
      return 'Processando áudio local';
    }
    if (player.isPlaying) return 'Reproduzindo áudio local';
    return 'Mídia local pronta';
  });

  selectSource(type: MediaSourceType): void {
    this.sourceState.set(null);
    this.settingsState.update((settings) => ({ ...settings, pitchSemitones: 0 }));
    this.playerState.set({
      ...DEFAULT_PLAYER_STATE,
      sourceType: type,
      status: 'empty',
    });
  }

  setSource(source: MediaSource | null): void {
    this.sourceState.set(source);
  }

  patchPlayer(patch: Partial<PlayerState>): void {
    this.playerState.update((player) => ({ ...player, ...patch }));
  }

  setPitch(value: number): void {
    const normalized = Math.round(value * 2) / 2;
    const pitchSemitones = Math.max(-12, Math.min(12, normalized === 0 ? 0 : normalized));
    this.settingsState.update((settings) => ({ ...settings, pitchSemitones }));
  }

  resetPitch(): void {
    this.setPitch(0);
  }

  setVolume(value: number): void {
    const volume = Math.max(0, Math.min(1, value));
    this.settingsState.update((settings) => ({ ...settings, volume }));
  }

  setMuted(muted: boolean): void {
    this.settingsState.update((settings) => ({ ...settings, muted }));
  }

  toggleMute(): void {
    this.settingsState.update((settings) => ({ ...settings, muted: !settings.muted }));
  }

  reset(): void {
    this.sourceState.set(null);
    this.settingsState.set({ ...DEFAULT_AUDIO_SETTINGS });
    this.playerState.set({ ...DEFAULT_PLAYER_STATE });
  }
}
