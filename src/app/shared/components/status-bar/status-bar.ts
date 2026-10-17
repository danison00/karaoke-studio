import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MediaSourceType } from '../../../core/models/media-source.model';

@Component({
  selector: 'app-status-bar',
  templateUrl: './status-bar.html',
  styleUrl: './status-bar.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusBar {
  readonly message = input('Selecione uma música para começar');
  readonly sourceType = input<MediaSourceType | null>(null);
  readonly dspReady = input(false);
}
