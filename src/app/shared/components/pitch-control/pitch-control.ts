import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

const PITCH_STEP = 0.5;
const MIN_PITCH = -12;
const MAX_PITCH = 12;

@Component({
  selector: 'app-pitch-control',
  templateUrl: './pitch-control.html',
  styleUrl: './pitch-control.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PitchControl {
  readonly value = input(0);
  readonly disabled = input(false);
  readonly pitchChange = output<number>();

  readonly label = computed(() => {
    const value = this.value();
    if (value === 0) return 'Tom original';
    const unit = Math.abs(value) === 1 ? 'semitom' : 'semitons';
    const formattedValue = Math.abs(value).toFixed(1).replace(/\.0$/, '').replace('.', ',');
    return `${value > 0 ? '+' : '−'}${formattedValue} ${unit}`;
  });

  decrease(): void {
    this.pitchChange.emit(Math.max(MIN_PITCH, this.value() - PITCH_STEP));
  }

  increase(): void {
    this.pitchChange.emit(Math.min(MAX_PITCH, this.value() + PITCH_STEP));
  }

  reset(): void {
    this.pitchChange.emit(0);
  }

  setFromInput(event: Event): void {
    this.pitchChange.emit(Number((event.target as HTMLInputElement).value));
  }
}
