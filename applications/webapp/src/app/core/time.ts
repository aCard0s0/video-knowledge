/**
 * Timestamp handling.
 *
 * The server serialises `LocalDateTime` with no zone: "2026-08-26T15:49:24.522757". A browser
 * parsing that treats it as *local* time. The container runs UTC and the operator's browser may
 * not — measured on this box: container 16:21 UTC, host 17:21 WEST, so every age read one hour
 * too old. Since "is this hung or just slow?" is the question the runs board exists to answer,
 * that skew is not cosmetic.
 *
 * ponytail: append Z and treat server time as UTC. Correct while the server runs UTC (true in
 * Docker). The real fix is Instant/OffsetDateTime server-side, which changes the wire contract
 * for the CLI and MCP clients too.
 */
export function parseServerTime(value: string | null | undefined): Date | null {
  if (!value) return null;
  // Already carries a zone (the two @DateTimeFormat fields on the YouTube DTOs do).
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/.test(value);
  const d = new Date(zoned ? value : `${value}Z`);
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

/** Absolute local wall-clock, for the tooltip behind every relative age. */
export function absoluteTime(value: string | null | undefined): string {
  const d = parseServerTime(value);
  return d ? d.toLocaleString() : '';
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
