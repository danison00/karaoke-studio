import { MediaSourceType } from './media-source.model';

export type PlaybackStatus =
  | 'empty'
  | 'loading'
  | 'preparing'
  | 'resolving'
  | 'streaming'
  | 'seeking'
  | 'degraded'
  | 'ready'
  | 'playing'
  | 'paused'
  | 'processing'
  | 'buffering'
  | 'ended'
  | 'error';

export interface PlayerState {
  sourceType: MediaSourceType | null;
  status: PlaybackStatus;
  isPlaying: boolean;
  isLoading: boolean;
  isBuffering: boolean;
  currentTime: number;
  duration: number;
  canProcessPitch: boolean;
  mediaName: string | null;
  errorMessage: string | null;
}

export const DEFAULT_PLAYER_STATE: PlayerState = {
  sourceType: null,
  status: 'empty',
  isPlaying: false,
  isLoading: false,
  isBuffering: false,
  currentTime: 0,
  duration: 0,
  canProcessPitch: false,
  mediaName: null,
  errorMessage: null,
};
