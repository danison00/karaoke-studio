import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaSourceSelector } from './media-source-selector';

describe('MediaSourceSelector', () => {
  let fixture: ComponentFixture<MediaSourceSelector>;
  let component: MediaSourceSelector;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [MediaSourceSelector] }).compileComponents();
    fixture = TestBed.createComponent(MediaSourceSelector);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
  });

  it('switches to YouTube and requests that source from the parent', () => {
    let selected: string | undefined;
    component.sourceTypeChange.subscribe((value) => (selected = value));

    fixture.debugElement.query(By.css('[data-testid="youtube-tab"]')).nativeElement.click();
    fixture.detectChanges();

    expect(selected).toBe('youtube');
  });

  it('emits a supported local media file selected by the user', () => {
    const file = new File(['audio'], 'minha-musica.mp3', { type: 'audio/mpeg' });
    let selected: File | undefined;
    component.localFileSelected.subscribe((value) => (selected = value));
    const input = fixture.debugElement.query(By.css('input[type="file"]')).nativeElement;
    Object.defineProperty(input, 'files', { value: [file] });

    input.dispatchEvent(new Event('change'));

    expect(selected).toBe(file);
    expect(component.validationMessage()).toBeNull();
  });

  it('rejects files outside the supported audio and video formats', () => {
    const file = new File(['text'], 'letra.txt', { type: 'text/plain' });

    component.acceptFiles([file]);

    expect(component.validationMessage()).toContain('MP3, WAV, MP4 ou WebM');
  });

  it('pastes the clipboard content into the YouTube URL input', async () => {
    fixture.componentRef.setInput('sourceType', 'youtube');
    fixture.detectChanges();

    const readText = vi.fn().mockResolvedValue(' https://youtu.be/abc123 ');
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText },
    });

    fixture.debugElement.query(By.css('[data-testid="paste-youtube-url"]')).nativeElement.click();
    await Promise.resolve();
    fixture.detectChanges();

    const input = fixture.debugElement.query(By.css('#youtube-url'))
      .nativeElement as HTMLInputElement;
    expect(readText).toHaveBeenCalledOnce();
    expect(component.youtubeUrl()).toBe('https://youtu.be/abc123');
    expect(input.value).toBe('https://youtu.be/abc123');
    expect(component.validationMessage()).toBeNull();
  });

  it('shows an error when the clipboard cannot be read', async () => {
    fixture.componentRef.setInput('sourceType', 'youtube');
    fixture.detectChanges();

    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { readText: vi.fn().mockRejectedValue(new Error('permission denied')) },
    });

    fixture.debugElement.query(By.css('[data-testid="paste-youtube-url"]')).nativeElement.click();
    await Promise.resolve();

    expect(component.validationMessage()).toContain('área de transferência');
  });
});
