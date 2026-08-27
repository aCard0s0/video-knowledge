/**
 * A source URL with its boilerplate off.
 *
 * Every label that shows one is clipped by `.truncate`, which is a fixed 34ch (18ch below 767px)
 * with the ellipsis on the **tail** — and a YouTube watch URL spends its first 32 characters
 * saying `https://www.youtube.com/watch?v=`. So the part that got cut was the video id, the only
 * thing that tells one row from the next, and a column of them all read
 * `https://www.youtube.com/watch?v=xxxx…`. Dropping the scheme and the `www.` leaves
 * `youtube.com/watch?v=dQw4w9WgXcQ` at 31 characters, which fits whole.
 *
 * Anything `URL` cannot parse comes back untouched: the value is whatever the operator pasted, and
 * a half-typed URL is exactly the row they need to be able to read.
 */
export function shortUrl(value: string | null | undefined): string {
  if (!value) return '';
  try {
    const url = new URL(value);
    return `${url.host.replace(/^www\./, '')}${url.pathname}${url.search}`;
  } catch {
    return value;
  }
}
