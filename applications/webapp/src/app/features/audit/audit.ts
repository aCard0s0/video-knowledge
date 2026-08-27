import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { AuditService, RunItemAuditEvent } from '../../api/generated';
import { EVENT_TYPES, RUN_STATUSES, blank, statusVar } from '../../core/domain';
import { absoluteTime, clockTime, dayLabel } from '../../core/time';
import { POLL_IDLE, Poller } from '../../core/poller';
import { firstFailure, valueOf } from '../../core/problem';
import { clampPage } from '../../core/paging';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { Fault } from '../../ui/fault';

const PAGE_SIZE = 50;

@Component({
  selector: 'vk-audit',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, Fault],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
})
export class Audit {
  private readonly audit = inject(AuditService);
  protected readonly poller = inject(Poller);

  protected readonly eventTypes = EVENT_TYPES;
  protected readonly statuses = RUN_STATUSES;

  protected readonly runId = signal('');
  protected readonly eventType = signal('');
  protected readonly status = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;

  protected readonly list = rxResource({
    params: () => ({
      runId: this.runId().trim(),
      eventType: this.eventType(),
      status: this.status(),
      from: isoOrEmpty(this.from()),
      to: isoOrEmpty(this.to()),
      page: this.page(),
    }),
    stream: ({ params }) =>
      this.audit.listEvents(
        params.runId || undefined,
        params.eventType || undefined,
        params.status || undefined,
        params.from || undefined,
        params.to || undefined,
        params.page,
        PAGE_SIZE,
      ),
  });

  constructor() {
    syncQueryParams({
      runId: this.runId,
      eventType: this.eventType,
      status: this.status,
      from: this.from,
      to: this.to,
      page: this.page,
    });
    // A shared link carries its page: ?page=9&eventType=ITEM_FAILED on a feed with three failures.
    clampPage(this.page, PAGE_SIZE, this.list);
    this.poller.every(
      () => POLL_IDLE,
      () => this.list.reload(),
    );
  }

  protected readonly rows = computed(() => valueOf(this.list)?.items ?? []);
  protected readonly total = computed(() => valueOf(this.list)?.total ?? 0);
  protected readonly failure = computed(() => firstFailure(this.list));

  private readonly days = computed(() => dayMarks(this.rows()));

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly clockTime = clockTime;
  protected readonly blank = blank;

  /** The day heading to draw above this row, or '' for every row that is not a day's first. */
  protected dayFor(id: string | undefined): string {
    return (id && this.days().get(id)) || '';
  }

  protected set(which: 'runId' | 'eventType' | 'status' | 'from' | 'to', value: string): void {
    this[which].set(value);
    this.page.set(0);
  }

  protected clear(): void {
    this.runId.set('');
    this.eventType.set('');
    this.status.set('');
    this.from.set('');
    this.to.set('');
    this.page.set(0);
  }

  protected readonly filtered = computed(
    () => !!(this.runId() || this.eventType() || this.status() || this.from() || this.to()),
  );
}

/**
 * Event id → the day heading to draw above it, for the first row of each calendar day only.
 *
 * Exported for the spec: this is the half of the When column that a clock cannot carry, and it is
 * ordering logic, not markup.
 */
export function dayMarks(events: RunItemAuditEvent[]): Map<string, string> {
  const marks = new Map<string, string>();
  let seen = '';
  for (const event of events) {
    const day = dayLabel(event.occurredAt);
    if (day && day !== seen && event.id) marks.set(event.id, day);
    seen = day;
  }
  return marks;
}

/**
 * `<input type="datetime-local">` yields local wall clock ("2026-08-26T10:00"); `fromDate` and
 * `toDate` are `OffsetDateTime` on the server. `toISOString()` is both halves of that in one call:
 * the local wall clock as the UTC instant it names, carrying the offset that says so.
 *
 * It used to `.slice(0, 19)` off the trailing `Z`, back when the server compared against naive
 * `LocalDateTime` and an offset would not parse. That was inverted by the `OffsetDateTime`
 * migration and every date filter answered
 * `400 Failed to convert value of type 'java.lang.String' to required type 'java.time.OffsetDateTime'`
 * — with the table replaced by the panel reporting it, re-fired every poll. A zoneless timestamp
 * is a bug at both ends now, which is the same rule `core/time.ts` states for the read path.
 */
export function isoOrEmpty(value: string): string {
  if (!value) return '';
  const local = new Date(value);
  if (Number.isNaN(local.getTime())) return '';
  return local.toISOString();
}
