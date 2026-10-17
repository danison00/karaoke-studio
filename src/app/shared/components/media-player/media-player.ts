import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { KaraokeStore } from '../../../core/services/karaoke-store.service';
import { MediaPlayerService } from '../../../core/services/media-player.service';

@Component({
  selector: 'app-media-player',
  templateUrl: './media-player.html',
  styleUrl: './media-player.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MediaPlayer {
  readonly store = inject(KaraokeStore);
  readonly localSource = computed(() => {
    const source = this.store.source();
    return source?.type === 'local' ? source : null;
  });
  readonly youtubeSource = computed(() => {
    const source = this.store.source();
    return source?.type === 'youtube' ? source : null;
  });
  private readonly playerService = inject(MediaPlayerService);
  private readonly media = viewChild.required<ElementRef<HTMLVideoElement>>('media');
  private readonly stage = viewChild.required<ElementRef<HTMLElement>>('stage');

  async loadLocal(file: File): Promise<void> {
    await this.playerService.loadLocal(file, this.media().nativeElement);
  }

  async loadYoutube(url: string): Promise<void> {
    await this.playerService.loadYoutube(url, this.media().nativeElement);
  }

  togglePlayback(): void {
    if (this.store.player().isPlaying) {
      this.playerService.pause();
      return;
    }
    void this.playerService.play();
  }

  restart(): void {
    this.playerService.restart();
  }

  seek(event: Event): void {
    const seconds = Number((event.target as HTMLInputElement).value);
    this.playerService.seek(seconds);
  }

  async toggleFullscreen(): Promise<void> {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
      return;
    }
    if (this.stage().nativeElement.requestFullscreen) {
      await this.stage().nativeElement.requestFullscreen();
    }
  }

  formatTime(seconds: number): string {
    if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
    const total = Math.floor(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;
    const minuteText = String(minutes).padStart(2, '0');
    const secondText = String(secs).padStart(2, '0');
    return hours > 0
      ? `${String(hours).padStart(2, '0')}:${minuteText}:${secondText}`
      : `${minuteText}:${secondText}`;
  }
}
