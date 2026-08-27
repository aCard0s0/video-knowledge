import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { syncQueryParams } from './url-state';

/** The last query params `syncQueryParams` asked the router to write. */
let written: Record<string, string | null>;

function setup(initialUrl: Record<string, string> = {}) {
  written = {};
  const navigate = vi.fn((_: unknown[], extras: { queryParams: Record<string, string | null> }) => {
    written = extras.queryParams;
    return Promise.resolve(true);
  });
  TestBed.configureTestingModule({
    providers: [
      { provide: Router, useValue: { navigate } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: { get: (k: string) => initialUrl[k] ?? null } } },
      },
    ],
  });
}

function sync(
  state: Record<string, WritableSignal<string | number | boolean>>,
  allowed?: Record<string, readonly string[]>,
) {
  TestBed.runInInjectionContext(() => syncQueryParams(state, allowed));
  TestBed.tick();
}

describe('syncQueryParams', () => {
  beforeEach(() => setup());

  it('keeps a signal at its declared value out of the URL', () => {
    sync({ page: signal(0), status: signal('ALL') });
    expect(written).toEqual({ page: null, status: null });
  });

  /** The regression: a filter that *starts* true has `false` as its interesting state. */
  it('writes false when the declared default is true', () => {
    const onlyNew = signal(true);
    sync({ onlyNew });
    expect(written['onlyNew']).toBeNull();

    onlyNew.set(false);
    TestBed.tick();
    expect(written['onlyNew']).toBe('false');
  });

  it('writes true when the declared default is false', () => {
    const live = signal(false);
    sync({ live });
    expect(written['live']).toBeNull();

    live.set(true);
    TestBed.tick();
    expect(written['live']).toBe('true');
  });

  /** 'ALL' used to be hardcoded as empty-ish; it is a status chip on two screens, not a concept. */
  it('does not treat ALL as universally empty', () => {
    const eventType = signal('');
    sync({ eventType });
    expect(written['eventType']).toBeNull();

    eventType.set('ALL');
    TestBed.tick();
    expect(written['eventType']).toBe('ALL');
  });

  it('reads a boolean back off the URL', () => {
    setup({ onlyNew: 'false' });
    const onlyNew = signal(true);
    sync({ onlyNew });
    expect(onlyNew()).toBe(false);
  });

  /**
   * A query param is whatever someone pasted, and `status` reaches a server-side `valueOf`.
   * `?status=BOGUS` used to arrive as a 400 carrying a raw Java enum name, under a control showing
   * its own default because no option matched.
   */
  it('ignores a URL value outside the allow-list and heals the URL', () => {
    setup({ status: 'BOGUS' });
    const status = signal('ALL');
    sync({ status }, { status: ['ALL', 'COMPLETED', 'FAILED'] });

    expect(status()).toBe('ALL');
    expect(written['status']).toBeNull();
  });

  it('still reads an allowed URL value', () => {
    setup({ status: 'FAILED' });
    const status = signal('ALL');
    sync({ status }, { status: ['ALL', 'COMPLETED', 'FAILED'] });

    expect(status()).toBe('FAILED');
    expect(written['status']).toBe('FAILED');
  });

  /** A key with no allow-list is unconstrained — most of them are free text. */
  it('leaves keys without an allow-list alone', () => {
    setup({ channel: 'anything at all', status: 'BOGUS' });
    const channel = signal('');
    const status = signal('ALL');
    sync({ channel, status }, { status: ['ALL'] });

    expect(channel()).toBe('anything at all');
    expect(status()).toBe('ALL');
  });

  it('takes the declared value as the default even when the URL disagrees on entry', () => {
    setup({ page: '3' });
    const page = signal(0);
    sync({ page });
    expect(page()).toBe(3);
    expect(written['page']).toBe('3');

    page.set(0);
    TestBed.tick();
    expect(written['page']).toBeNull();
  });
});
