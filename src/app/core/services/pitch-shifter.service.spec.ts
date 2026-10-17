import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PITCH_NODE_FACTORY,
  PITCH_PROCESSOR_REGISTER,
  PitchShifterService,
  pitchFactorForSemitones,
} from './pitch-shifter.service';

describe('PitchShifterService', () => {
  const pitchParam = {
    value: 0,
    cancelScheduledValues: vi.fn(),
    setValueAtTime: vi.fn(),
    linearRampToValueAtTime: vi.fn(),
  };
  const playbackRate = { value: 0 };
  const pitch = { value: 0 };
  const node = {
    pitchSemitones: pitchParam,
    playbackRate,
    pitch,
    connect: vi.fn(),
    disconnect: vi.fn(),
  };
  const source = { connect: vi.fn(), disconnect: vi.fn() };
  const destination = {};
  const context = { currentTime: 4 } as BaseAudioContext;
  const register = vi.fn(async () => undefined);
  const createNode = vi.fn(() => node);

  beforeEach(() => {
    vi.clearAllMocks();
    pitchParam.value = 0;
    TestBed.configureTestingModule({
      providers: [
        PitchShifterService,
        { provide: PITCH_PROCESSOR_REGISTER, useValue: register },
        { provide: PITCH_NODE_FACTORY, useValue: createNode },
      ],
    });
  });

  it('registers one stereo worklet node per audio context', async () => {
    const service = TestBed.inject(PitchShifterService);

    await service.initialize(context);
    await service.initialize(context);

    expect(register).toHaveBeenCalledOnce();
    expect(createNode).toHaveBeenCalledOnce();
    expect(playbackRate.value).toBe(1);
    expect(pitch.value).toBe(1);
    expect(service.ready()).toBe(true);
  });

  it('connects source through the processor before the destination', async () => {
    const service = TestBed.inject(PitchShifterService);
    await service.initialize(context);

    service.connect(source as unknown as AudioNode, destination as AudioNode);

    expect(source.connect).toHaveBeenCalledWith(node);
    expect(node.connect).toHaveBeenCalledWith(destination);
  });

  it('smooths and clamps live semitone changes', async () => {
    const service = TestBed.inject(PitchShifterService);
    await service.initialize(context);

    service.setSemitones(20);

    expect(pitchParam.cancelScheduledValues).toHaveBeenCalledWith(4);
    expect(pitchParam.linearRampToValueAtTime).toHaveBeenCalledWith(12, 4.04);
    expect(service.semitones()).toBe(12);
  });

  it('preserves half-semitone pitch changes', async () => {
    const service = TestBed.inject(PitchShifterService);
    await service.initialize(context);

    service.setSemitones(0.5);

    expect(pitchParam.linearRampToValueAtTime).toHaveBeenCalledWith(0.5, 4.04);
    expect(service.semitones()).toBe(0.5);
  });

  it('calculates octave pitch factors without changing playback speed', () => {
    expect(pitchFactorForSemitones(12)).toBe(2);
    expect(pitchFactorForSemitones(-12)).toBe(0.5);
    expect(pitchFactorForSemitones(0)).toBe(1);
  });

  it('exposes a useful error when the worklet cannot be loaded', async () => {
    register.mockRejectedValueOnce(new Error('processor indisponível'));
    const service = TestBed.inject(PitchShifterService);

    await expect(service.initialize(context)).rejects.toThrow('processor indisponível');

    expect(service.ready()).toBe(false);
    expect(service.errorMessage()).toBe('processor indisponível');
  });
});
