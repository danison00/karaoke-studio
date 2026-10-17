import { describe, expect, it } from 'vitest';
import { extractYoutubeVideoId } from './youtube-url.util';

describe('extractYoutubeVideoId', () => {
  it.each([
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'dQw4w9WgXcQ'],
    ['https://youtu.be/dQw4w9WgXcQ?t=42', 'dQw4w9WgXcQ'],
    ['https://youtube.com/shorts/dQw4w9WgXcQ', 'dQw4w9WgXcQ'],
    ['youtube.com/watch?v=dQw4w9WgXcQ&list=abc', 'dQw4w9WgXcQ'],
  ])('extracts a valid id from %s', (url, expected) => {
    expect(extractYoutubeVideoId(url)).toBe(expected);
  });

  it.each([
    'https://youtube.example.com/watch?v=dQw4w9WgXcQ',
    'https://notyoutube.com/watch?v=dQw4w9WgXcQ',
    'https://youtube.com/watch?v=short',
    'https://youtu.be/dQw4w9WgXcQextra',
    'not a url',
    '',
  ])('rejects an unsafe or malformed URL: %s', (url) => {
    expect(extractYoutubeVideoId(url)).toBeNull();
  });
});
