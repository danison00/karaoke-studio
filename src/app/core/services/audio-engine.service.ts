import { Injectable, InjectionToken, inject } from '@angular/core';
import { PitchShifterService } from './pitch-shifter.service';

export type AudioContextFactory = () => AudioContext;

export const AUDIO_CONTEXT_FACTORY = new InjectionToken<AudioContextFactory>(
  'AUDIO_CONTEXT_FACTORY',
  {
    providedIn: 'root',
    factory: () => () => new AudioContext(),
  },
);

@Injectable({ providedIn: 'root' })
export class AudioEngineService {
  private readonly contextFactory = inject(AUDIO_CONTEXT_FACTORY);
  private readonly pitchShifter = inject(PitchShifterService);
  private readonly sourceNodes = new WeakMap<HTMLMediaElement, MediaElementAudioSourceNode>();

  private context: AudioContext | null = null;
  private gainNode: GainNode | null = null;
  private currentElement: HTMLMediaElement | null = null;
  private currentSource: MediaElementAudioSourceNode | null = null;
  private pitchProcessingAvailable = false;
  private volume = 0.8;
  private muted = false;

  get audioContext(): AudioContext | null {
    return this.context;
  }

  get sourceNode(): MediaElementAudioSourceNode | null {
    return this.currentSource;
  }

  get outputNode(): GainNode | null {
    return this.gainNode;
  }

  async attach(element: HTMLMediaElement): Promise<boolean> {
    if (this.currentElement === element && this.currentSource) return this.pitchProcessingAvailable;

    let graph: { context: AudioContext; gain: GainNode };
    try {
      graph = this.ensureGraph();
    } catch {
      this.currentElement = element;
      this.currentSource = null;
      this.pitchProcessingAvailable = false;
      this.applyNativeVolume();
      return false;
    }

    const { context, gain } = graph;

    this.pitchShifter.disconnect();
    const source =
      this.sourceNodes.get(element) ?? context.createMediaElementSource(element);
    this.sourceNodes.set(element, source);

    try {
      element.playbackRate = 1;
      element.defaultPlaybackRate = 1;
      await this.pitchShifter.initialize(context);
      this.pitchShifter.connect(source, gain);
      this.pitchProcessingAvailable = true;
    } catch {
      source.disconnect();
      source.connect(gain);
      this.pitchProcessingAvailable = false;
    }

    this.currentElement = element;
    this.currentSource = source;
    // Keep the native element at unity gain while Web Audio owns the volume.
    // If Web Audio is unavailable, setVolume/setMuted use this element directly.
    element.volume = 1;
    element.muted = false;
    return this.pitchProcessingAvailable;
  }

  async resume(): Promise<void> {
    if (this.context?.state === 'suspended') {
      await this.context.resume();
    }
  }

  setVolume(volume: number): void {
    this.volume = Math.max(0, Math.min(1, volume));
    if (!this.hasWebAudioOutput()) {
      this.applyNativeVolume();
      return;
    }
    this.applyGain();
  }

  setMuted(muted: boolean): void {
    this.muted = muted;
    if (!this.hasWebAudioOutput()) {
      this.applyNativeVolume();
      return;
    }
    this.applyGain();
  }

  setPitch(semitones: number): void {
    this.pitchShifter.setSemitones(semitones);
  }

  disposeSource(): void {
    this.pitchShifter.disconnect();
    this.currentSource = null;
    this.currentElement = null;
    this.pitchProcessingAvailable = false;
  }

  async destroy(): Promise<void> {
    this.disposeSource();
    this.gainNode?.disconnect();
    this.gainNode = null;
    if (this.context && this.context.state !== 'closed') {
      await this.context.close();
    }
    this.context = null;
    this.pitchProcessingAvailable = false;
  }

  private ensureGraph(): { context: AudioContext; gain: GainNode } {
    if (!this.context) {
      this.context = this.contextFactory();
    }
    if (!this.gainNode) {
      this.gainNode = this.context.createGain();
      this.gainNode.gain.value = this.muted ? 0 : this.volume;
      this.gainNode.connect(this.context.destination);
    }
    return { context: this.context, gain: this.gainNode };
  }

  private applyGain(): void {
    if (!this.context || !this.gainNode) return;
    this.gainNode.gain.cancelScheduledValues(this.context.currentTime);
    this.gainNode.gain.setTargetAtTime(
      this.muted ? 0 : this.volume,
      this.context.currentTime,
      0.01,
    );
  }

  private hasWebAudioOutput(): boolean {
    return this.context !== null && this.gainNode !== null && this.currentSource !== null;
  }

  private applyNativeVolume(): void {
    if (!this.currentElement) return;
    this.currentElement.muted = this.muted;
    this.currentElement.volume = this.muted ? 0 : this.volume;
  }
}
