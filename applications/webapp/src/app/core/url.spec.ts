import { describe, expect, it } from 'vitest';

import { shortUrl } from './url';

describe('shortUrl', () => {
  it('drops the 32 characters of boilerplate that a 34ch clip was eating the video id for', () => {
    expect(shortUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('youtube.com/watch?v=dQw4w9WgXcQ');
    expect(shortUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ').length).toBeLessThan(34);
  });

  it('keeps a host that is not www, and any path that is not a watch URL', () => {
    expect(shortUrl('https://vimeo.com/123456789')).toBe('vimeo.com/123456789');
    expect(shortUrl('http://media.example.co.uk/a/b.mp4')).toBe('media.example.co.uk/a/b.mp4');
  });

  it('hands back what it cannot parse — a half-typed URL is the row you need to read', () => {
    expect(shortUrl('www.youtube.com/watch?v=TYPO')).toBe('www.youtube.com/watch?v=TYPO');
    expect(shortUrl('not a url at all')).toBe('not a url at all');
  });

  it('has nothing to show for the API’s empty string', () => {
    expect(shortUrl('')).toBe('');
    expect(shortUrl(undefined)).toBe('');
  });
});
