import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { RunWatch } from './run-watch';
import { WatchedRun } from '../core/watch-run';

/**
 * The panel two screens draw. Only the channel screen has no DOM test of its own, so what is
 * asserted here is the part each caller supplies differently: the head, and whether an item's
 * channel is a link.
 */
const ITEM = {
  itemId: 'item-1',
  status: 'IN_PROGRESS',
  url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  videoTitle: '',
  channelName: 'Rick TV',
};

function handle(): WatchedRun {
  return {
    runId: signal('7f3c1a2b-0000-4000-8000-000000000000'),
    detail: () => ({ id: '7f3c1a2b-0000-4000-8000-000000000000', status: 'IN_PROGRESS' }),
    items: () => [ITEM],
    lane: () => [],
  } as unknown as WatchedRun;
}

@Component({
  selector: 'vk-test-host',
  imports: [RunWatch],
  template: `<vk-run-watch [watch]="watch" [headline]="headline()" [channelLink]="link()" />`,
})
class Host {
  readonly watch = handle();
  readonly headline = signal('');
  readonly link = signal(false);
}

function panel(headline = '', link = false) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.headline.set(headline);
  fixture.componentInstance.link.set(link);
  TestBed.tick();
  return fixture.nativeElement as HTMLElement;
}

describe('RunWatch', () => {
  /** A reopened run was not started by this visit, so "started · N accepted" would be a claim. */
  it('names the run it is watching when the caller has nothing to claim', () => {
    expect(panel().querySelector('.eyebrow')!.textContent).toContain('watching · 7f3c1a2b');
  });

  it('uses the caller’s headline when there is one', () => {
    expect(panel('started · 2 accepted').querySelector('.eyebrow')!.textContent).toContain(
      'started · 2 accepted',
    );
  });

  /** The id comes from the handle, not from the loaded run: the link works before the run lands. */
  it('links to the full run', () => {
    expect(panel().querySelector('.panel-head a')!.getAttribute('href')).toBe(
      '/runs/7f3c1a2b-0000-4000-8000-000000000000',
    );
  });

  /**
   * On a channel screen every row would repeat the channel the operator is already standing on, so
   * the link is opt-in — and the label itself must still render either way.
   */
  it('leaves the channel unlinked unless the caller asks for it', () => {
    expect(panel().querySelector('.url a')).toBeNull();
    expect(panel().querySelector('.url')!.textContent).toContain('youtube.com/watch?v=dQw4w9WgXcQ');

    const linked = panel('', true).querySelector('.url a')!;
    expect(linked.textContent).toContain('Rick TV');
    expect(linked.getAttribute('href')).toBe('/videos?channel=Rick%20TV');
  });
});
