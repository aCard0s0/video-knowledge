import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { AuditService } from '../../api/generated';
import { ALL_PHASES, ERROR_CODES, EVENT_TYPES, RUN_STATUSES, blank, statusVar } from '../../core/domain';
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
  private readonly poller = inject(Poller);

  protected readonly eventTypes = EVENT_TYPES;
  protected readonly statuses = RUN_STATUSES;
  protected readonly phases = ALL_PHASES;
  protected readonly errorCodes = ERROR_CODES;

  protected readonly runId = signal('');
  protected readonly eventType = signal('');
  protected readonly status = signal('');
  protected readonly phase = signal('');
  protected readonly errorCode = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;

  /**
   * The run id the operator typed is only sent when it is a whole uuid.
   *
   * `runId` is a `UUID` parameter, so anything else is a 400 with a raw Java conversion message —
   * and the id this screen *shows* is `runId.slice(0, 8)`, which is exactly what an operator copies
   * off a row. Holding the request back turns that into a hint beside the field.
   */
  protected readonly runIdInvalid = computed(() => {
    const value = this.runId().trim();
    return !!value && !UUID.test(value);
  });

  protected readonly list = rxResource({
    params: () => ({
      runId: this.runIdInvalid() ? '' : this.runId().trim(),
      eventType: this.eventType(),
      status: this.status(),
      phase: this.phase(),
      errorCode: this.errorCode(),
      from: isoOrEmpty(this.from()),
      to: isoOrEmpty(this.to()),
      page: this.page(),
    }),
    stream: ({ params }) =>
      this.audit.listEvents(
        params.runId || undefined,
        params.eventType || undefined,
        params.status || undefined,
        params.phase || undefined,
        params.errorCode || undefined,
        params.from || undefined,
        params.to || undefined,
        params.page,
        PAGE_SIZE,
      ),
  });

  constructor() {
    // All four selects reach a server-side enum parse, and `''` is the "any" option each starts on.
    // A pasted `?status=BOGUS` used to go straight through to a 400 carrying a raw Java enum name,
    // under a select showing "any" because nothing matched.
    syncQueryParams(
      {
        runId: this.runId,
        eventType: this.eventType,
        status: this.status,
        phase: this.phase,
        errorCode: this.errorCode,
        from: this.from,
        to: this.to,
        page: this.page,
      },
      {
        eventType: ['', ...this.eventTypes],
        status: ['', ...this.statuses],
        phase: ['', ...this.phases],
        errorCode: ['', ...this.errorCodes],
      },
    );
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

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly clockTime = clockTime;
  protected readonly dayLabel = dayLabel;
  protected readonly blank = blank;

  /** Announced, not just rendered: the pager's count is the only thing a filter visibly changes. */
  protected readonly said = computed(() => {
    if (!this.list.hasValue()) return '';
    const total = this.total();
    if (total === 0) return 'No events match these filters.';
    const first = this.page() * PAGE_SIZE + 1;
    return `${total} events match. Showing ${first}–${Math.min(total, first + PAGE_SIZE - 1)}.`;
  });

  protected set(which: FilterKey, value: string): void {
    this[which].set(value);
    this.page.set(0);
  }

  protected clear(): void {
    for (const key of FILTER_KEYS) this[key].set('');
    this.page.set(0);
  }

  protected readonly filtered = computed(() => FILTER_KEYS.some((key) => !!this[key]()));
}

const FILTER_KEYS = ['runId', 'eventType', 'status', 'phase', 'errorCode', 'from', 'to'] as const;
type FilterKey = (typeof FILTER_KEYS)[number];

/** Whole uuid, which is what `?runId` accepts — see `runIdInvalid`. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * `<input type="datetime-local">` yields local wall clock; `fromDate`/`toDate` are
 * `OffsetDateTime`. `toISOString()` is both halves in one call: that wall clock as the UTC instant
 * it names, carrying the offset that says so. Trimming the `Z` off — right only while the server
 * compared naive `LocalDateTime` — 400ed every date filter.
 */
export function isoOrEmpty(value: string): string {
  if (!value) return '';
  const local = new Date(value);
  if (Number.isNaN(local.getTime())) return '';
  return local.toISOString();
}
