import { describe, expect, it } from 'vitest';

import { crumb } from './app';

describe('crumb', () => {
  it('names the section on a list screen and has no leaf', () => {
    expect(crumb('/runs')).toEqual({ section: 'runs', leaf: '' });
  });

  it('cuts a uuid to the eight characters the run screen prints', () => {
    expect(crumb('/runs/710a9419-3f2b-4d1e-9a77-0c1d5e8b2a44')).toEqual({
      section: 'runs',
      leaf: '710a9419',
    });
  });

  it('keeps a short id whole', () => {
    expect(crumb('/videos/9Vy8oXBEIQg')).toEqual({ section: 'videos', leaf: '9Vy8oXBEIQg' });
  });

  it('drops query and fragment — a filter is not a place', () => {
    expect(crumb('/audit?eventType=ITEM_FAILED&page=2')).toEqual({ section: 'audit', leaf: '' });
  });

  it('has nothing to show before the redirect off /', () => {
    expect(crumb('/')).toEqual({ section: '', leaf: '' });
  });
});
