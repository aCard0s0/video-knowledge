import { describe, expect, it } from 'vitest';

import { pinnedToTop } from './channel-detail';

/**
 * The ingest bar is `position: sticky; top: 0` and paints a shadow once it pins. There is no
 * `:stuck` selector, so an IntersectionObserver answers it — and the shape that answer takes is
 * the whole point of these four cases.
 */
describe('pinnedToTop', () => {
  it('is pinned once the top edge reaches the viewport top', () => {
    expect(pinnedToTop({ top: 0 })).toBe(true);
  });

  it('stays pinned while sticky holds it against the edge', () => {
    // Sticky clamps at 0, but a sub-pixel layout can report a hair under it.
    expect(pinnedToTop({ top: -0.5 })).toBe(true);
  });

  it('is not pinned while it still sits in the flow', () => {
    expect(pinnedToTop({ top: 240 })).toBe(false);
  });

  /**
   * The regression this replaced `intersectionRatio < 1` for. A panel below the fold is not
   * intersecting at all, so the ratio reads 0 — indistinguishable from pinned-and-clipped. On any
   * channel whose watch panel pushed the bar off screen, the shadow was painted on load.
   */
  it('is not pinned when it is below the fold, where the ratio also reads 0', () => {
    expect(pinnedToTop({ top: 1400 })).toBe(false);
  });
});
