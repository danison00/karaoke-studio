import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { KaraokeStore } from '../../../core/services/karaoke-store.service';
import { MediaPlayerService } from '../../../core/services/media-player.service';
import { MediaPlayer } from './media-player';

describe('MediaPlayer', () => {
  const playerService = {
    play: vi.fn(async () => undefined),
    pause: vi.fn(),
    restart: vi.fn(),
    seek: vi.fn(),
    loadLocal: vi.fn(async () => undefined),
    loadYoutube: vi.fn(async () => undefined),
  };
  let fixture: ComponentFixture<MediaPlayer>;
  let component: MediaPlayer;
  let store: KaraokeStore;

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [MediaPlayer],
      providers: [
        KaraokeStore,
        { provide: MediaPlayerService, useValue: playerService },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MediaPlayer);
    component = fixture.componentInstance;
    store = TestBed.inject(KaraokeStore);
    fixture.detectChanges();
  });

  it('formats short and long media durations', () => {
    expect(component.formatTime(65)).toBe('01:05');
    expect(component.formatTime(3661)).toBe('01:01:01');
    expect(component.formatTime(Number.NaN)).toBe('00:00');
  });

  it('toggles play and pause from the custom player controls', async () => {
    store.selectSource('local');
    store.setSource({
      type: 'local',
      file: new File(['audio'], 'song.mp3', { type: 'audio/mpeg' }),
      objectUrl: 'blob:test',
      kind: 'audio',
    });
    store.patchPlayer({ status: 'ready' });
    fixture.detectChanges();

    fixture.debugElement.query(By.css('[data-testid="play-toggle"]')).nativeElement.click();
    expect(playerService.play).toHaveBeenCalledOnce();

    store.patchPlayer({ isPlaying: true });
    fixture.detectChanges();
    fixture.debugElement.query(By.css('[data-testid="play-toggle"]')).nativeElement.click();
    expect(playerService.pause).toHaveBeenCalledOnce();
  });

  it('seeks using the progress slider', () => {
    store.selectSource('local');
    store.patchPlayer({ duration: 200, currentTime: 50 });
    fixture.detectChanges();
    const slider = fixture.debugElement.query(By.css('[data-testid="progress"]')).nativeElement;
    slider.value = '125';

    slider.dispatchEvent(new Event('input'));

    expect(playerService.seek).toHaveBeenCalledWith(125);
  });

  it('loads and controls YouTube through the same media element', async () => {
    store.selectSource('youtube');
    store.setSource({ type: 'youtube', videoId: 'dQw4w9WgXcQ', url: 'https://youtu.be/dQw4w9WgXcQ' });
    store.patchPlayer({ status: 'ready' });
    fixture.detectChanges();

    await component.loadYoutube('https://youtu.be/dQw4w9WgXcQ');
    fixture.debugElement.query(By.css('[data-testid="play-toggle"]')).nativeElement.click();

    expect(playerService.loadYoutube).toHaveBeenCalledWith('https://youtu.be/dQw4w9WgXcQ', expect.any(HTMLElement));
    expect(playerService.play).toHaveBeenCalledOnce();
  });
});
