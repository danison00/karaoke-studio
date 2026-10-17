const VIDEO_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com']);

export function extractYoutubeVideoId(value: string): string | null {
  const candidate = value.trim();
  if (!candidate) return null;

  let url: URL;
  try {
    url = new URL(candidate.includes('://') ? candidate : `https://${candidate}`);
  } catch {
    return null;
  }

  const hostname = url.hostname.toLowerCase();
  let videoId: string | null = null;

  if (hostname === 'youtu.be') {
    const segments = url.pathname.split('/').filter(Boolean);
    videoId = segments.length === 1 ? segments[0] : null;
  } else if (YOUTUBE_HOSTS.has(hostname)) {
    if (url.pathname === '/watch') {
      videoId = url.searchParams.get('v');
    } else {
      const segments = url.pathname.split('/').filter(Boolean);
      videoId = segments.length === 2 && segments[0] === 'shorts' ? segments[1] : null;
    }
  }

  return videoId && VIDEO_ID_PATTERN.test(videoId) ? videoId : null;
}
