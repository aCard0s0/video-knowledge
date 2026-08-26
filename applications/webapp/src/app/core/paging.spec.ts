import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { clampPage } from './paging';

/** Stands in for a resource: `hasValue` false is loading *or* errored — both say nothing. */
function res(total: number | undefined, hasValue = true) {
  return { hasValue: () => hasValue, value: () => (total === undefined ? undefined : { total }) };
}

function run(page: number, total: number | undefined, hasValue = true): number {
  const p = signal(page);
  TestBed.runInInjectionContext(() => clampPage(p, 25, res(total, hasValue)));
  TestBed.tick();
  return p();
}

describe('clampPage', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('pulls back a page past the end — the row deleted off page 1 of 26', () => {
    expect(run(1, 25)).toBe(0);
  });

  it('leaves a page that still holds rows', () => {
    expect(run(1, 26)).toBe(1);
  });

  it('leaves the first page alone whatever the total', () => {
    expect(run(0, 0)).toBe(0);
  });

  it('says nothing while loading or errored — a hiccup must not move the page', () => {
    expect(run(3, 25, false)).toBe(3);
  });

  it('clamps an idle-then-empty list to the only page there is', () => {
    expect(run(3, 0)).toBe(0);
  });
});
