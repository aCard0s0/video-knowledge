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

function sync(state: Record<string, WritableSignal<string | number | boolean>>) {
  TestBed.runInInjectionContext(() => syncQueryParams(state));
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

  it('reads a boolean back off the URL', () => {
    setup({ onlyNew: 'false' });
    const onlyNew = signal(true);
    sync({ onlyNew });
    expect(onlyNew()).toBe(false);
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
