import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUDIO_CONTEXT_FACTORY, AudioEngineService } from './audio-engine.service';
import { PitchShifterService } from './pitch-shifter.service';

describe('AudioEngineService', () => {
  const sourceNode = {
    connect: vi.fn(),
    disconnect: vi.fn(),
  };
  const gainParam = {
    cancelScheduledValues: vi.fn(),
    setTargetAtTime: vi.fn(),
    value: 1,
  };
  const gainNode = {
    gain: gainParam,
    connect: vi.fn(),
    disconnect: vi.fn(),
  };
  const context = {
    state: 'suspended',
    currentTime: 2,
    destination: {},
    createGain: vi.fn(() => gainNode),
    createMediaElementSource: vi.fn(() => sourceNode),
    resume: vi.fn(async () => undefined),
    close: vi.fn(async () => undefined),
  };
  const factory = vi.fn(() => context as unknown as AudioContext);
  const pitchShifter = {
    initialize: vi.fn(async () => undefined),
    connect: vi.fn(),
    disconnect: vi.fn(),
    setSemitones: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    context.state = 'suspended';
    TestBed.configureTestingModule({
      providers: [
        AudioEngineService,
        { provide: AUDIO_CONTEXT_FACTORY, useValue: factory },
        { provide: PitchShifterService, useValue: pitchShifter },
      ],
    });
  });

  it('creates only one media element source for the same element', async () => {
    const service = TestBed.inject(AudioEngineService);
    const element = document.createElement('audio');

    await service.attach(element);
    await service.attach(element);

    expect(factory).toHaveBeenCalledTimes(1);
    expect(context.createMediaElementSource).toHaveBeenCalledTimes(1);
    expect(pitchShifter.initialize).toHaveBeenCalledOnce();
    expect(pitchShifter.connect).toHaveBeenCalledWith(sourceNode, gainNode);
  });

  it('routes media directly when the audio worklet is unavailable', async () => {
    pitchShifter.initialize.mockRejectedValueOnce(new Error("Can't find variable: audioWorkletNode"));
    const service = TestBed.inject(AudioEngineService);

    const pitchAvailable = await service.attach(document.createElement('audio'));

    expect(pitchAvailable).toBe(false);
    expect(sourceNode.connect).toHaveBeenCalledWith(gainNode);
    expect(pitchShifter.connect).not.toHaveBeenCalled();
  });

  it('resumes a suspended context before playback', async () => {
    const service = TestBed.inject(AudioEngineService);
    await service.attach(document.createElement('audio'));

    await service.resume();

    expect(context.resume).toHaveBeenCalledOnce();
  });

  it('changes gain for volume and mute without creating another context', async () => {
    const service = TestBed.inject(AudioEngineService);
    await service.attach(document.createElement('audio'));

    service.setVolume(0.45);
    service.setMuted(true);

    expect(factory).toHaveBeenCalledTimes(1);
    expect(gainParam.setTargetAtTime).toHaveBeenNthCalledWith(1, 0.45, 2, 0.01);
    expect(gainParam.setTargetAtTime).toHaveBeenNthCalledWith(2, 0, 2, 0.01);
  });

  it('uses the native media volume when Web Audio cannot be initialized', async () => {
    factory.mockImplementationOnce(() => {
      throw new Error('AudioContext indisponível');
    });
    const service = TestBed.inject(AudioEngineService);
    const element = document.createElement('audio');

    const pitchAvailable = await service.attach(element);
    service.setVolume(0.35);
    service.setMuted(true);

    expect(pitchAvailable).toBe(false);
    expect(element.volume).toBe(0);
    expect(element.muted).toBe(true);

    service.setMuted(false);

    expect(element.volume).toBeCloseTo(0.35);
    expect(element.muted).toBe(false);
  });

  it('changes pitch in semitones while keeping media playback speed untouched', async () => {
    const service = TestBed.inject(AudioEngineService);
    const element = document.createElement('audio');
    element.playbackRate = 1;
    await service.attach(element);

    service.setPitch(-5);

    expect(pitchShifter.setSemitones).toHaveBeenCalledWith(-5);
    expect(element.playbackRate).toBe(1);
  });
});
