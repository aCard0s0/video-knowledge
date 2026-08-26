import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { AUDIT_MAX_PAGES, AUDIT_PAGE, auditTail } from './audit';
import { PageResponseRunItemAuditEvent, RunItemAuditEvent } from '../api/generated';

/** A server whose feed is `total` events long, ascending, paged at AUDIT_PAGE. */
function server(total: number) {
  const asked: number[] = [];
  const pipelines = {
    auditRun(_runId: string, page: number, size: number): Observable<PageResponseRunItemAuditEvent> {
      asked.push(page);
      const from = page * size;
      const items: RunItemAuditEvent[] = [];
      for (let i = from; i < Math.min(total, from + size); i++) {
        items.push({ id: String(i), itemId: 'i1', eventType: 'ITEM_PHASE_ENTERED' });
      }
      return of({ items, page, size, total });
    },
  };
  return { pipelines, asked };
}

function tail(total: number) {
  const { pipelines, asked } = server(total);
  let got: PageResponseRunItemAuditEvent | undefined;
  auditTail(pipelines, 'run-1').subscribe((r) => (got = r));
  return { asked, ids: (got?.items ?? []).map((e) => Number(e.id)), total: got?.total };
}

describe('auditTail', () => {
  it('makes one request when the whole trail fits', () => {
    const { asked, ids } = tail(120);
    expect(asked).toEqual([0]);
    expect(ids).toHaveLength(120);
  });

  /**
   * The regression. `floor((total-1)/500)` is page 1 here, and page 1 holds exactly one event — so
   * taking the last page alone returned 1 of 501 and every lane but one read "never ran".
   */
  it('keeps the whole trail when the last page holds a single event', () => {
    const { asked, ids } = tail(AUDIT_PAGE + 1);
    expect(asked).toEqual([0, 1]);
    expect(ids).toHaveLength(AUDIT_PAGE + 1);
    expect(ids[ids.length - 1]).toBe(AUDIT_PAGE);
  });

  it('takes the newest pages, in ascending order, up to the cap', () => {
    const total = AUDIT_PAGE * 5; // ~100-item run
    const { asked, ids } = tail(total);

    // Page 0 is the probe that reports `total`; its body is dropped once the window starts past it.
    expect(asked).toEqual([0, 1, 2, 3, 4]);
    expect(ids).toHaveLength(AUDIT_PAGE * AUDIT_MAX_PAGES);
    // Ascending, and it is the *tail* that survives: buildLane's lastEntered scans from the end.
    expect(ids[0]).toBe(AUDIT_PAGE);
    expect(ids[ids.length - 1]).toBe(total - 1);
    expect(ids).toEqual([...ids].sort((a, b) => a - b));
  });

  /** The banner on the run screen reads total vs items, so the count must stay the server's. */
  it('reports the real total even when capped', () => {
    expect(tail(AUDIT_PAGE * 5).total).toBe(AUDIT_PAGE * 5);
  });

  it('handles an empty trail', () => {
    const { asked, ids } = tail(0);
    expect(asked).toEqual([0]);
    expect(ids).toEqual([]);
  });
});
