export type MediaSourceType = 'local' | 'youtube';

export type LocalMediaKind = 'audio' | 'video';

export interface LocalMediaSource {
  type: 'local';
  file: File;
  objectUrl: string;
  kind: LocalMediaKind;
}

export interface YoutubeMediaSource {
  type: 'youtube';
  videoId: string;
  url: string;
  title?: string;
  mode?: 'PROGRESSIVE' | 'MUXED' | 'HLS';
  preparationId?: string;
}

export type MediaSource = LocalMediaSource | YoutubeMediaSource;
