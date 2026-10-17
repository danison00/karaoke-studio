import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';
import { KaraokeStore } from './karaoke-store.service';

describe('KaraokeStore', () => {
  let store: KaraokeStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(KaraokeStore);
  });

  it('starts with safe local playback defaults', () => {
    expect(store.settings()).toEqual({ pitchSemitones: 0, volume: 0.8, muted: false });
    expect(store.player().sourceType).toBeNull();
    expect(store.player().canProcessPitch).toBe(false);
  });

  it('normalizes pitch to half-semitone steps between minus and plus twelve', () => {
    store.setPitch(15);
    expect(store.settings().pitchSemitones).toBe(12);

    store.setPitch(-15);
    expect(store.settings().pitchSemitones).toBe(-12);

    store.setPitch(2.6);
    expect(store.settings().pitchSemitones).toBe(2.5);

    store.setPitch(0.5);
    expect(store.settings().pitchSemitones).toBe(0.5);

    store.resetPitch();
    expect(store.settings().pitchSemitones).toBe(0);
  });

  it('clamps volume and toggles mute without losing the selected level', () => {
    store.setVolume(2);
    expect(store.settings().volume).toBe(1);

    store.toggleMute();
    expect(store.settings()).toEqual({ pitchSemitones: 0, volume: 1, muted: true });
  });

  it('resets playback and pitch when switching sources', () => {
    store.selectSource('local');
    store.setPitch(5);
    store.patchPlayer({ isPlaying: true, currentTime: 42, canProcessPitch: true });

    store.selectSource('youtube');

    expect(store.settings().pitchSemitones).toBe(0);
    expect(store.player().sourceType).toBe('youtube');
    expect(store.player().isPlaying).toBe(false);
    expect(store.player().currentTime).toBe(0);
    expect(store.player().canProcessPitch).toBe(false);
    expect(store.statusMessage()).toBe('Vídeo YouTube pronto');
  });

  it('reports YouTube pitch processing when the media element is attached', () => {
    store.selectSource('youtube');
    store.patchPlayer({ canProcessPitch: true, isPlaying: true });
    store.setPitch(0.5);

    expect(store.statusMessage()).toBe('Processando áudio YouTube');
  });
});
