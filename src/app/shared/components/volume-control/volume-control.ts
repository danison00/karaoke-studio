import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';

@Component({
  selector: 'app-volume-control',
  imports: [DecimalPipe],
  templateUrl: './volume-control.html',
  styleUrl: './volume-control.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VolumeControl {
  readonly volume = input(0.8);
  readonly muted = input(false);
  readonly volumeChange = output<number>();
  readonly mutedChange = output<boolean>();
  readonly displayedVolume = signal(0.8);

  constructor() {
    effect(() => this.displayedVolume.set(this.volume()));
  }

  updateVolume(event: Event): void {
    const volume = Number((event.target as HTMLInputElement).value) / 100;
    this.displayedVolume.set(volume);
    this.volumeChange.emit(volume);
  }

  toggleMute(): void {
    this.mutedChange.emit(!this.muted());
  }
}
