import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, viewChild } from '@angular/core';
import { MediaSourceType } from '../../core/models/media-source.model';
import { AudioEngineService } from '../../core/services/audio-engine.service';
import { KaraokeStore } from '../../core/services/karaoke-store.service';
import { MediaPlayerService } from '../../core/services/media-player.service';
import { PitchShifterService } from '../../core/services/pitch-shifter.service';
import { extractYoutubeVideoId } from '../../core/utils/youtube-url.util';
import { MediaPlayer } from '../../shared/components/media-player/media-player';
import { MediaSourceSelector } from '../../shared/components/media-source-selector/media-source-selector';
import { PitchControl } from '../../shared/components/pitch-control/pitch-control';
import { StatusBar } from '../../shared/components/status-bar/status-bar';
import { VolumeControl } from '../../shared/components/volume-control/volume-control';

@Component({
  selector: 'app-karaoke-page',
  imports: [MediaSourceSelector, MediaPlayer, PitchControl, VolumeControl, StatusBar],
  templateUrl: './karaoke-page.component.html',
  styleUrl: './karaoke-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KaraokePageComponent implements OnDestroy {
  readonly store = inject(KaraokeStore);
  readonly pitchShifter = inject(PitchShifterService);
  readonly selectedSource = computed<MediaSourceType>(() => this.store.player().sourceType ?? 'local');
  readonly pitchDisabled = computed(() => !this.store.player().canProcessPitch);

  private readonly audioEngine = inject(AudioEngineService);
  private readonly mediaPlayerService = inject(MediaPlayerService);
  private readonly player = viewChild.required(MediaPlayer);

  changeSource(type: MediaSourceType): void {
    if (this.store.player().sourceType === type && !this.store.source()) return;
    this.mediaPlayerService.dispose();
    this.audioEngine.setPitch(0);
    this.store.selectSource(type);
  }

  async loadLocalFile(file: File): Promise<void> {
    try {
      await this.player().loadLocal(file);
      const settings = this.store.settings();
      this.audioEngine.setVolume(settings.volume);
      this.audioEngine.setMuted(settings.muted);
    } catch {
      // O serviço já publica uma mensagem de erro acionável no estado.
    }
  }

  async submitYoutubeUrl(url: string): Promise<void> {
    const videoId = extractYoutubeVideoId(url);
    if (!videoId) {
      this.store.selectSource('youtube');
      this.store.patchPlayer({
        status: 'error',
        errorMessage: 'Cole uma URL válida do YouTube (watch, youtu.be ou shorts).',
      });
      return;
    }

    this.store.selectSource('youtube');
    this.store.setSource({ type: 'youtube', videoId, url });
    this.store.patchPlayer({ status: 'loading', isLoading: true, mediaName: url, errorMessage: null });
    try {
      await this.player().loadYoutube(url);
    } catch {
      // O adapter já publicou o erro de carregamento no estado.
    }
  }

  changePitch(value: number): void {
    this.store.setPitch(value);
    this.audioEngine.setPitch(this.store.settings().pitchSemitones);
    if (this.store.player().canProcessPitch && this.store.player().isPlaying) {
      this.store.patchPlayer({ status: value === 0 ? 'playing' : 'processing' });
    }
  }

  changeVolume(value: number): void {
    this.store.setVolume(value);
    this.audioEngine.setVolume(this.store.settings().volume);
  }

  changeMuted(muted: boolean): void {
    this.store.setMuted(muted);
    this.audioEngine.setMuted(muted);
  }

  ngOnDestroy(): void {
    this.mediaPlayerService.dispose();
  }
}
