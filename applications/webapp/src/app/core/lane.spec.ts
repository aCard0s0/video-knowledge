import { describe, expect, it } from 'vitest';

import { buildLane, buildLanes, laneTotalMs } from './lane';
import { RunItem, RunItemAuditEvent } from '../api/generated';

/** Server timestamps are naive LocalDateTime in UTC. */
const at = (s: string) => `2026-08-26T14:41:${s}`;

function ev(
  eventType: string,
  phase: string,
  seconds: string,
  extra: Partial<RunItemAuditEvent> = {},
): RunItemAuditEvent {
  return { itemId: 'i1', eventType, phase, occurredAt: at(seconds), attempt: 1, ...extra };
}

describe('buildLane', () => {
  it('measures completed phases and marks the failed one, from a real failed run', () => {
    const item: RunItem = {
      itemId: 'i1',
      status: 'FAILED',
      phase: 'DONE', // the run/item reports DONE even on failure — must not drive the lane
      failedPhase: 'OCR',
      phaseUpdatedAt: at('53.245188'),
      attempt: 1,
    };
    const events = [
      ev('ITEM_CREATED', 'CREATED', '21.000280'),
      ev('ITEM_PHASE_ENTERED', 'METADATA', '21.027352'),
      ev('ITEM_PHASE_COMPLETED', 'METADATA', '23.575604'),
      ev('ITEM_PHASE_ENTERED', 'DOWNLOAD', '23.578343'),
      ev('ITEM_PHASE_COMPLETED', 'DOWNLOAD', '35.000000'),
      ev('ITEM_PHASE_ENTERED', 'OCR', '50.000000'),
    ];

    const lane = buildLane(item, events, Date.parse('2026-08-26T15:00:00Z'));
    const byPhase = Object.fromEntries(lane.map((s) => [s.phase, s]));

    expect(byPhase['METADATA'].state).toBe('done');
    expect(byPhase['METADATA'].ms).toBeCloseTo(2548, 0);
    expect(byPhase['DOWNLOAD'].ms).toBeCloseTo(11422, 0);

    // Never entered, but before the frontier: deliberately skipped.
    expect(byPhase['TRANSCRIBE'].state).toBe('skipped');

    // The failure is attributed to OCR and measured against phaseUpdatedAt, not to "DONE".
    expect(byPhase['OCR'].state).toBe('failed');
    expect(byPhase['OCR'].ms).toBeCloseTo(3245, 0);

    // Past the frontier: never reached, so pending — not "skipped".
    expect(byPhase['FUSE'].state).toBe('pending');
    expect(byPhase['CONTEXT'].state).toBe('pending');

    // CREATED and DONE are run markers, never steps. The type forbids them; assert the values
    // too, so widening LanePhase later cannot quietly put a marker on the lane.
    const names: string[] = lane.map((s) => s.phase);
    expect(names).toHaveLength(10);
    expect(names).not.toContain('CREATED');
    expect(names).not.toContain('DONE');
    expect(laneTotalMs(lane)).toBeGreaterThan(0);
  });

  it('grows the live segment against now and ignores older attempts', () => {
    const item: RunItem = { itemId: 'i1', status: 'IN_PROGRESS', phase: 'TRANSCRIBE', attempt: 2 };
    const events = [
      // attempt 1 took a different path and must not be drawn
      ev('ITEM_PHASE_ENTERED', 'METADATA', '10.000000'),
      ev('ITEM_PHASE_COMPLETED', 'METADATA', '40.000000'),
      ev('ITEM_PHASE_ENTERED', 'METADATA', '50.000000', { attempt: 2 }),
      ev('ITEM_PHASE_COMPLETED', 'METADATA', '52.000000', { attempt: 2 }),
      ev('ITEM_PHASE_ENTERED', 'TRANSCRIBE', '52.500000', { attempt: 2 }),
    ];

    const lane = buildLane(item, events, Date.parse('2026-08-26T14:42:02.500Z')); // 10s after entering
    const byPhase = Object.fromEntries(lane.map((s) => [s.phase, s]));

    expect(byPhase['METADATA'].ms).toBeCloseTo(2000, 0); // attempt 2, not attempt 1's 30s
    expect(byPhase['TRANSCRIBE'].state).toBe('live');
    expect(byPhase['TRANSCRIBE'].ms).toBeCloseTo(10000, 0);
    expect(byPhase['OCR'].state).toBe('pending');
  });

  it('draws a phase that died without blame as failed, never as complete', () => {
    const item: RunItem = {
      itemId: 'i1',
      status: 'FAILED',
      phase: 'DOWNLOAD',
      phaseUpdatedAt: at('30.000000'),
    };
    const events = [ev('ITEM_PHASE_ENTERED', 'DOWNLOAD', '20.000000')];

    const lane = buildLane(item, events, Date.parse('2026-08-26T15:00:00Z'));
    const download = lane.find((s) => s.phase === 'DOWNLOAD')!;

    expect(download.state).toBe('failed');
    expect(download.ms).toBeCloseTo(10000, 0);
  });

  it('marks a cancelled item cancelled, not failed — DUPLICATE_VIDEO is a decision', () => {
    const item: RunItem = {
      itemId: 'i1',
      status: 'CANCELLED',
      phase: 'DONE',
      failedPhase: 'METADATA',
      phaseUpdatedAt: at('30.000000'),
      attempt: 1,
    };
    const events = [ev('ITEM_PHASE_ENTERED', 'METADATA', '20.000000')];

    const lane = buildLane(item, events, Date.parse('2026-08-26T15:00:00Z'));
    const metadata = lane.find((s) => s.phase === 'METADATA')!;

    expect(metadata.state).toBe('cancelled');
    expect(metadata.ms).toBeCloseTo(10000, 0);
    expect(lane.some((s) => s.state === 'failed')).toBe(false);
  });

  it('never reaches for a frontier at CREATED — a reaped item is unreached, not skipped', () => {
    // A run item the reconciler swept while it was still queued blames the CREATED run marker.
    // indexOf answers -1 for it, which used to leave every phase "not reached" AND no cap at all,
    // so the lane of a FAILED item announced itself complete.
    const item: RunItem = {
      itemId: 'i1',
      status: 'FAILED',
      phase: 'DONE',
      failedPhase: 'CREATED',
      phaseUpdatedAt: at('30.000000'),
      attempt: 1,
    };
    const events = [ev('ITEM_CREATED', 'CREATED', '20.000000')];

    const lane = buildLane(item, events, Date.parse('2026-08-26T15:00:00Z'));

    expect(lane).toHaveLength(10);
    expect(lane.every((s) => s.state === 'pending')).toBe(true);
    // Not "skipped": nothing was turned off, the item simply never got to run.
    expect(lane.some((s) => s.state === 'skipped')).toBe(false);
    expect(laneTotalMs(lane)).toBe(0);
  });

  it('keeps other items out of this item’s lane', () => {
    const item: RunItem = { itemId: 'i1', status: 'IN_PROGRESS' };
    const events = [
      ev('ITEM_PHASE_ENTERED', 'METADATA', '10.000000', { itemId: 'other' }),
      ev('ITEM_PHASE_COMPLETED', 'METADATA', '20.000000', { itemId: 'other' }),
    ];

    const lane = buildLane(item, events, Date.parse('2026-08-26T15:00:00Z'));
    expect(lane.every((s) => s.ms === null)).toBe(true);
  });
});

describe('buildLanes', () => {
  /** Grouping is per item: mixing two items' trails would double-count time on both lanes. */
  it('gives each item only its own events', () => {
    const items: RunItem[] = [
      { itemId: 'i1', status: 'COMPLETED', failedPhase: 'DONE', phaseUpdatedAt: at('30.000000'), attempt: 1 },
      { itemId: 'i2', status: 'FAILED', failedPhase: 'DOWNLOAD', phaseUpdatedAt: at('40.000000'), attempt: 1 },
    ];
    const events = [
      ev('ITEM_PHASE_ENTERED', 'METADATA', '21.000000', { itemId: 'i1' }),
      ev('ITEM_PHASE_ENTERED', 'METADATA', '22.000000', { itemId: 'i2' }),
      ev('ITEM_PHASE_COMPLETED', 'METADATA', '23.000000', { itemId: 'i1' }),
      ev('ITEM_PHASE_ENTERED', 'DOWNLOAD', '24.000000', { itemId: 'i2' }),
    ];

    const lanes = buildLanes(items, events, Date.parse(`${at('45.000000')}Z`));

    const first = lanes.get('i1')!;
    const second = lanes.get('i2')!;
    expect(first[0]).toMatchObject({ phase: 'METADATA', state: 'done', ms: 2000 });
    // i2 never completed METADATA, so it must not borrow i1's ITEM_PHASE_COMPLETED.
    expect(second[0].state).not.toBe('done');
    expect(second[1]).toMatchObject({ phase: 'DOWNLOAD', state: 'failed' });
  });
});
