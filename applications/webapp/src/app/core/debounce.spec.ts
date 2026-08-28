import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { debouncedWrite } from './debounce';

describe('debouncedWrite', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('applies the last value once, after the pause', () => {
    const seen: string[] = [];
    const write = runInInjectionContext(TestBed.inject(Injector), () =>
      debouncedWrite<string>((v) => seen.push(v), 250),
    );

    write('7');
    write('71');
    write('710a');
    vi.advanceTimersByTime(249);
    expect(seen).toEqual([]);

    vi.advanceTimersByTime(1);
    expect(seen).toEqual(['710a']);
  });

  it('drops a pending write when the screen goes', () => {
    const seen: string[] = [];
    const write = runInInjectionContext(TestBed.inject(Injector), () =>
      debouncedWrite<string>((v) => seen.push(v), 250),
    );

    write('half-typed');
    TestBed.resetTestingModule(); // destroys the injector, and with it the pending timer
    vi.advanceTimersByTime(1000);
    expect(seen).toEqual([]);
  });
});
