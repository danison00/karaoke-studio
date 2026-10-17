export interface AudioSettings {
  pitchSemitones: number;
  volume: number;
  muted: boolean;
}

export const DEFAULT_AUDIO_SETTINGS: AudioSettings = {
  pitchSemitones: 0,
  volume: 0.8,
  muted: false,
};
