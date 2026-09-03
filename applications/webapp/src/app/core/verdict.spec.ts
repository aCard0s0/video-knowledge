import { describe, expect, it } from 'vitest';

import { acceptedOf, rejectsOf } from './verdict';
import { ItemResultStatusEnum } from '../api/generated';

/**
 * The rule these two carry is that **a 2xx is not an acceptance**: the same body answers with a
 * per-item verdict, and every item can be REJECTED under a 202. Four screens read it.
 */
const RESPONSE = {
  runId: 'r1',
  items: [
    { url: 'https://a', status: ItemResultStatusEnum.Accepted },
    {
      url: 'https://b',
      status: ItemResultStatusEnum.Rejected,
      reason: 'run item is already running',
    },
    { url: 'https://c', status: ItemResultStatusEnum.Rejected },
  ],
};

describe('rejectsOf', () => {
  it('keeps only the declined items', () => {
    expect(rejectsOf(RESPONSE)).toEqual([
      {
        url: 'https://b',
        status: ItemResultStatusEnum.Rejected,
        reason: 'run item is already running',
      },
      { url: 'https://c', status: ItemResultStatusEnum.Rejected },
    ]);
  });

  it('has nothing to say before a request has answered', () => {
    expect(rejectsOf(null)).toEqual([]);
  });
});

describe('acceptedOf', () => {
  /** The count screens report as "started": `items.length` counted the refusals as successes. */
  it('counts only what the server took', () => {
    expect(acceptedOf(RESPONSE)).toEqual([
      { url: 'https://a', status: ItemResultStatusEnum.Accepted },
    ]);
  });

  it('is empty for a response that accepted nothing at all', () => {
    expect(acceptedOf({ runId: undefined, items: [RESPONSE.items[1]] })).toEqual([]);
  });
});
