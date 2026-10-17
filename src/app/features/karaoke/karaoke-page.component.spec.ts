import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AudioEngineService } from '../../core/services/audio-engine.service';
import { KaraokeStore } from '../../core/services/karaoke-store.service';
import { MediaPlayerService } from '../../core/services/media-player.service';
import { KaraokePageComponent } from './karaoke-page.component';

describe('KaraokePageComponent', () => {
  const audioEngine = {
    setPitch: vi.fn(),
    setVolume: vi.fn(),
    setMuted: vi.fn(),
  };
  const mediaPlayer = { dispose: vi.fn(), loadYoutube: vi.fn(async () => undefined) };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [KaraokePageComponent],
      providers: [
        KaraokeStore,
        { provide: AudioEngineService, useValue: audioEngine },
        { provide: MediaPlayerService, useValue: mediaPlayer },
      ],
    });
  });

  it('renders the studio and keeps store and audio engine controls synchronized', () => {
    const fixture = TestBed.createComponent(KaraokePageComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const store = TestBed.inject(KaraokeStore);

    component.changePitch(3);
    component.changeVolume(0.45);
    component.changeMuted(true);

    expect(store.settings()).toEqual({ pitchSemitones: 3, volume: 0.45, muted: true });
    expect(audioEngine.setPitch).toHaveBeenCalledWith(3);
    expect(audioEngine.setVolume).toHaveBeenCalledWith(0.45);
    expect(audioEngine.setMuted).toHaveBeenCalledWith(true);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Karaokê Studio');
  });

  it('disposes the current local source before switching adapters', () => {
    const fixture = TestBed.createComponent(KaraokePageComponent);
    fixture.detectChanges();

    fixture.componentInstance.changeSource('youtube');

    expect(mediaPlayer.dispose).toHaveBeenCalledOnce();
    expect(TestBed.inject(KaraokeStore).player().sourceType).toBe('youtube');
  });

  it('rejects malformed YouTube URLs before creating a player', async () => {
    const fixture = TestBed.createComponent(KaraokePageComponent);
    fixture.detectChanges();

    await fixture.componentInstance.submitYoutubeUrl('https://notyoutube.com/watch?v=dQw4w9WgXcQ');

    expect(TestBed.inject(KaraokeStore).player().errorMessage).toContain('URL válida do YouTube');
    expect(mediaPlayer.loadYoutube).not.toHaveBeenCalled();
  });

  it('loads a valid URL through the same media element and keeps pitch available after attach', async () => {
    const fixture = TestBed.createComponent(KaraokePageComponent);
    fixture.detectChanges();

    await fixture.componentInstance.submitYoutubeUrl('https://youtu.be/dQw4w9WgXcQ');

    expect(mediaPlayer.loadYoutube).toHaveBeenCalledWith('https://youtu.be/dQw4w9WgXcQ', expect.any(HTMLElement));
  });
});
