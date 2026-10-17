import { Injectable, InjectionToken, inject, signal } from '@angular/core';

const PROCESSOR_URL = '/audio-worklets/soundtouch-processor.js';
const MIN_SEMITONES = -12;
const MAX_SEMITONES = 12;
const RAMP_SECONDS = 0.04;

export interface PitchProcessorNode extends AudioNode {
  readonly pitch: AudioParam;
  readonly pitchSemitones: AudioParam;
  readonly playbackRate: AudioParam;
}

export type PitchProcessorRegister = (
  context: BaseAudioContext,
  processorUrl: string | URL,
) => Promise<void>;

export type PitchNodeFactory = (
  context: BaseAudioContext,
) => PitchProcessorNode | Promise<PitchProcessorNode>;

export const PITCH_PROCESSOR_REGISTER = new InjectionToken<PitchProcessorRegister>(
  'PITCH_PROCESSOR_REGISTER',
  {
    providedIn: 'root',
    factory: () => async (context, processorUrl) => {
      const { SoundTouchNode } = await import('@soundtouchjs/audio-worklet');
      await SoundTouchNode.register(context, processorUrl);
    },
  },
);

export const PITCH_NODE_FACTORY = new InjectionToken<PitchNodeFactory>('PITCH_NODE_FACTORY', {
  providedIn: 'root',
  factory: () => async (context) => {
    const { SoundTouchNode } = await import('@soundtouchjs/audio-worklet');
    return new SoundTouchNode({ context, outputChannelCount: 2 });
  },
});

export function pitchFactorForSemitones(semitones: number): number {
  return 2 ** (semitones / 12);
}

@Injectable({ providedIn: 'root' })
export class PitchShifterService {
  private readonly registerProcessor = inject(PITCH_PROCESSOR_REGISTER);
  private readonly createNode = inject(PITCH_NODE_FACTORY);

  private context: BaseAudioContext | null = null;
  private node: PitchProcessorNode | null = null;
  private source: AudioNode | null = null;
  private initialization: Promise<void> | null = null;

  readonly ready = signal(false);
  readonly semitones = signal(0);
  readonly errorMessage = signal<string | null>(null);

  async initialize(context: BaseAudioContext): Promise<void> {
    if (this.context === context && this.node) return;
    if (this.context === context && this.initialization) return this.initialization;

    this.disconnect();
    this.context = context;
    this.ready.set(false);
    this.errorMessage.set(null);

    const initialization = this.registerProcessor(context, PROCESSOR_URL)
      .then(async () => {
        if (this.context !== context) return;
        const node = await this.createNode(context);
        node.pitch.value = 1;
        node.playbackRate.value = 1;
        node.pitchSemitones.value = this.semitones();
        this.node = node;
        this.ready.set(true);
      })
      .catch((error: unknown) => {
        if (this.context === context) {
          this.context = null;
          this.node = null;
          this.ready.set(false);
          this.errorMessage.set(
            error instanceof Error ? error.message : 'Não foi possível iniciar o processador de tom.',
          );
        }
        throw error;
      })
      .finally(() => {
        if (this.initialization === initialization) {
          this.initialization = null;
        }
      });

    this.initialization = initialization;
    return initialization;
  }

  connect(source: AudioNode, destination: AudioNode): void {
    if (!this.node) {
      throw new Error('O processador de tom ainda não foi inicializado.');
    }

    this.disconnectGraph();
    source.connect(this.node);
    this.node.connect(destination);
    this.source = source;
  }

  setSemitones(semitones: number): void {
    const normalized = Math.round(semitones * 2) / 2;
    const nextValue = Math.max(
      MIN_SEMITONES,
      Math.min(MAX_SEMITONES, normalized === 0 ? 0 : normalized),
    );
    this.semitones.set(nextValue);

    if (!this.context || !this.node) return;
    const now = this.context.currentTime;
    const parameter = this.node.pitchSemitones;
    parameter.cancelScheduledValues(now);
    parameter.setValueAtTime(parameter.value, now);
    parameter.linearRampToValueAtTime(nextValue, now + RAMP_SECONDS);
  }

  disconnect(): void {
    this.disconnectGraph();
    this.node?.disconnect();
    this.node = null;
    this.context = null;
    this.initialization = null;
    this.ready.set(false);
  }

  private disconnectGraph(): void {
    this.source?.disconnect();
    this.source = null;
    this.node?.disconnect();
  }
}
