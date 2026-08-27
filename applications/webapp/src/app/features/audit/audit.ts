import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { AuditService } from '../../api/generated';
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
  private readonly poller = inject(Poller);

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

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly clockTime = clockTime;
  protected readonly dayLabel = dayLabel;
  protected readonly blank = blank;

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
