import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Lane } from './lane';
import { LaneSegment } from '../core/lane';
import { LANE_PHASES } from '../core/domain';

/**
 * The lane's `status` input carries the one case no segment can express: the item is over and
 * nothing on the track ran. Three screens draw this component and the channel catalog shipped
 * without passing it, so a batch item reaped while still queued announced itself as **complete**
 * on that screen and as failed on the other two.
 */
const QUEUED: LaneSegment[] = LANE_PHASES.map((phase) => ({
  phase,
  state: 'pending',
  ms: null,
  merged: 1,
}));

@Component({
  selector: 'vk-test-host',
  imports: [Lane],
  template: '<vk-lane [segments]="segments" [status]="status()" (pick)="picked = $event" />',
})
class Host {
  readonly segments = QUEUED;
  readonly status = signal<string | undefined>(undefined);
  picked = '';
}

function lane(status?: string) {
  // Reset per call, not per test: these assert two statuses against each other in one `it`.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.status.set(status);
  TestBed.tick();
  const el = fixture.nativeElement as HTMLElement;
  return {
    host: fixture.componentInstance,
    summary: el.querySelector('.lane')!.getAttribute('aria-label'),
  };
}

describe('Lane', () => {
  it('calls an item that died before phase one failed, not complete', () => {
    expect(lane('FAILED').summary).toBe('Phase timeline, failed before any phase ran');
    expect(lane('CANCELLED').summary).toBe('Phase timeline, cancelled before any phase ran');
  });

  /** The regression: the same segments with no status read as a finished item. */
  it('cannot tell the difference without the status input', () => {
    expect(lane().summary).toBe('Phase timeline, complete');
  });

  it('separates a queued item from a finished one', () => {
    expect(lane('PENDING').summary).toBe('Phase timeline, not started');
  });
});
