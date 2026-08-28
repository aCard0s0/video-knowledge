/**
 * Timestamp handling.
 *
 * Every server timestamp carries an explicit offset: the entities are `OffsetDateTime` written at
 * UTC, so the wire form is "2026-08-26T15:49:24.522757Z" and `new Date` reads it correctly wherever
 * the browser sits.
 *
 * This used to append a `Z` to a zoneless `LocalDateTime`, which was right only while the server
 * ran with -Duser.timezone=UTC — true in Docker, false on a host in any other zone, and measured
 * on this box as every age reading one hour too old (container 16:21 UTC, host 17:21 WEST). The
 * appended Z is deliberately *not* kept as a fallback: it would silently re-assume UTC for any
 * field that lost its offset, which is how the skew hid in the first place. A zoneless timestamp
 * is now a server bug, and should read as one.
 */
export function parseServerTime(value: string | null | undefined): Date | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function msBetween(from: string | null | undefined, to: string | null | undefined): number | null {
  const a = parseServerTime(from);
  const b = parseServerTime(to);
  return a && b ? b.getTime() - a.getTime() : null;
}

/** Compact duration: 940ms, 3.4s, 2m 05s, 1h 12m. */
export function humanDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—';
  if (ms < 0) return '0ms';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(Math.floor(s % 60)).padStart(2, '0')}s`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

/** Age against a caller-supplied now, so a signal tick refreshes every row at once. */
export function humanAge(value: string | null | undefined, nowMs: number): string {
  const d = parseServerTime(value);
  if (!d) return '—';
  const diff = nowMs - d.getTime();
  if (diff < 0) return 'just now';
  return `${humanDuration(diff)} ago`;
}

/**
 * Age without seconds, for a reading that does not change by the second.
 *
 * A channel syncs on a half-hour schedule, so "46.2s ago" ticking to "47.3s ago" is precision the
 * number does not have — and a column of them re-renders every poll for no information. The
 * tooltip beside it still carries the exact instant, which is where precision belongs.
 */
export function humanAgeCoarse(value: string | null | undefined, nowMs: number): string {
  const d = parseServerTime(value);
  if (!d) return '—';
  const minutes = Math.floor((nowMs - d.getTime()) / 60_000);
  // Covers a clock a little ahead of the server as well as the first minute.
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${String(minutes % 60).padStart(2, '0')}m ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

/** Absolute local wall-clock, for the tooltip behind every relative age. */
export function absoluteTime(value: string | null | undefined): string {
  const d = parseServerTime(value);
  return d ? d.toLocaleString() : '';
}

const CLOCK = new Intl.DateTimeFormat(undefined, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  fractionalSecondDigits: 3,
  hour12: false,
});

/**
 * Time of day with milliseconds, for rows that all share one age.
 *
 * Sixteen events inside a 32-second run every read "6h 42m ago", so a relative age can neither
 * order them nor tell them apart — and the phase transitions that matter are 3ms apart. The
 * relative age still answers "is this run stale?", which is a question the header asks once.
 */
export function clockTime(value: string | null | undefined): string {
  const d = parseServerTime(value);
  return d ? CLOCK.format(d) : '—';
}

const DAY = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

/**
 * The local calendar day, for a feed that spans several of them: `clockTime` alone cannot say which
 * midnight 21:22:07 is on. Doubles as the grouping key — same day, same string — so nothing else
 * has to define "same day", and local like the clock it sits beside.
 */
export function dayLabel(value: string | null | undefined): string {
  const d = parseServerTime(value);
  return d ? DAY.format(d) : '';
}

/** Seconds → mm:ss / h:mm:ss, matching the player's own readout. */
export function timecode(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '--:--';
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}
