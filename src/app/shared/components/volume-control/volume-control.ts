import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

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

  updateVolume(event: Event): void {
    this.volumeChange.emit(Number((event.target as HTMLInputElement).value) / 100);
  }

  toggleMute(): void {
    this.mutedChange.emit(!this.muted());
  }
}
