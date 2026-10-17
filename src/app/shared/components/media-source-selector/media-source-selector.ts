import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { MediaSourceType } from '../../../core/models/media-source.model';

const SUPPORTED_EXTENSIONS = ['mp3', 'wav', 'mp4', 'webm'];

@Component({
  selector: 'app-media-source-selector',
  templateUrl: './media-source-selector.html',
  styleUrl: './media-source-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MediaSourceSelector {
  readonly sourceType = input<MediaSourceType>('local');
  readonly sourceTypeChange = output<MediaSourceType>();
  readonly localFileSelected = output<File>();
  readonly youtubeUrlSubmitted = output<string>();

  readonly validationMessage = signal<string | null>(null);
  readonly youtubeUrl = signal('');
  readonly isDragging = signal(false);

  requestSource(type: MediaSourceType): void {
    this.validationMessage.set(null);
    this.sourceTypeChange.emit(type);
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.acceptFiles(input.files ? Array.from(input.files) : []);
    input.value = '';
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
    this.acceptFiles(event.dataTransfer?.files ? Array.from(event.dataTransfer.files) : []);
  }

  acceptFiles(files: File[]): void {
    const file = files[0];
    if (!file) return;

    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    const mediaMime = file.type.startsWith('audio/') || file.type.startsWith('video/');
    if (!mediaMime || !SUPPORTED_EXTENSIONS.includes(extension)) {
      this.validationMessage.set('Escolha um arquivo MP3, WAV, MP4 ou WebM compatível.');
      return;
    }

    this.validationMessage.set(null);
    this.localFileSelected.emit(file);
  }

  updateYoutubeUrl(event: Event): void {
    this.youtubeUrl.set((event.target as HTMLInputElement).value);
  }

  async pasteYoutubeUrl(): Promise<void> {
    this.validationMessage.set(null);

    try {
      if (!navigator.clipboard?.readText) {
        throw new Error('Clipboard API indisponível');
      }

      const clipboardText = (await navigator.clipboard.readText()).trim();
      if (!clipboardText) {
        this.validationMessage.set('A área de transferência está vazia.');
        return;
      }

      this.youtubeUrl.set(clipboardText);
    } catch {
      this.validationMessage.set(
        'Não foi possível acessar a área de transferência. Verifique a permissão do navegador.',
      );
    }
  }

  submitYoutubeUrl(): void {
    const url = this.youtubeUrl().trim();
    if (!url) {
      this.validationMessage.set('Cole uma URL do YouTube para continuar.');
      return;
    }

    this.validationMessage.set(null);
    this.youtubeUrlSubmitted.emit(url);
  }
}
