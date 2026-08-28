import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { App, crumb, resolveTheme } from './app';
import { HealthService } from './api/generated';

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

describe('resolveTheme', () => {
  it('follows the OS until the operator chooses', () => {
    expect(resolveTheme(null, true)).toBe('dark');
    expect(resolveTheme(null, false)).toBe('light');
  });

  it('lets a stored choice win over the OS in both directions', () => {
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });

  it('treats anything else in storage as no choice at all', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('', false)).toBe('light');
  });
});

/**
 * The shell's three controls, pressed rather than called: every earlier check forced their state
 * instead, which cannot catch a handler that is never wired to its button.
 */
@Component({ template: 'stub' })
class Stub {}

const HEALTH = {
  readiness: () => of({ ready: true, checks: { db: 'ok' } }),
  llmStatus: () =>
    of({ reachable: true, provider: 'ollama', baseUrl: 'http://llm:11434', runningModels: [] }),
};

function shell() {
  const fixture = TestBed.createComponent(App);
  TestBed.tick();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

/**
 * The test environment has no `matchMedia`, and stubbing it is also the only way to exercise the
 * OS-preference branch: no headless browser here can emulate a dark desktop.
 */
let prefersDark = false;

function stubMatchMedia() {
  (window as unknown as Record<string, unknown>)['matchMedia'] = (media: string) => ({
    media,
    matches: prefersDark,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  });
}

/** Alt+3 as the browser reports it: `code` is the digit, `key` is `£` or `™` on macOS. */
function altDigit(code: string) {
  const event = new KeyboardEvent('keydown', {
    code,
    altKey: true,
    bubbles: true,
    cancelable: true,
  });
  document.dispatchEvent(event);
  return event;
}

describe('App shell', () => {
  beforeEach(() => {
    localStorage.clear();
    delete document.documentElement.dataset['theme'];
    prefersDark = false;
    stubMatchMedia();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'ingest', component: Stub },
          { path: 'runs', component: Stub },
          { path: 'videos', component: Stub },
        ]),
        { provide: HealthService, useValue: HEALTH },
      ],
    });
  });

  afterEach(() => {
    localStorage.clear();
    delete document.documentElement.dataset['theme'];
  });

  it('applies a theme to the document before anything is clicked', () => {
    shell();
    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('follows a dark desktop when nothing was ever chosen', () => {
    prefersDark = true;
    shell();
    expect(document.documentElement.dataset['theme']).toBe('dark');
  });

  it('lets a stored light choice beat a dark desktop', () => {
    prefersDark = true;
    localStorage.setItem('vk.theme', 'light');
    shell();
    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('switches theme on click, and remembers it', () => {
    const { el } = shell();
    const button = el.querySelector<HTMLButtonElement>('.clock button')!;

    button.click();
    TestBed.tick();

    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(localStorage.getItem('vk.theme')).toBe('dark');

    button.click();
    TestBed.tick();

    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem('vk.theme')).toBe('light');
  });

  it('keeps the theme-color meta in step with the surface', () => {
    const meta = document.createElement('meta');
    meta.name = 'theme-color';
    document.head.append(meta);

    const { el } = shell();
    expect(meta.getAttribute('content')).toBe('#f8fafc');

    el.querySelector<HTMLButtonElement>('.clock button')!.click();
    TestBed.tick();
    expect(meta.getAttribute('content')).toBe('#020617');

    meta.remove();
  });

  it('collapses the rail on click, and remembers it', () => {
    const { el } = shell();
    const button = el.querySelector<HTMLButtonElement>('.nav-toggle')!;

    expect(el.querySelector('.frame')!.classList.contains('collapsed')).toBe(false);

    button.click();
    TestBed.tick();

    expect(el.querySelector('.frame')!.classList.contains('collapsed')).toBe(true);
    expect(localStorage.getItem('vk.nav-collapsed')).toBe('1');
    expect(button.getAttribute('aria-expanded')).toBe('false');
  });

  it('starts collapsed when that is what was stored', () => {
    localStorage.setItem('vk.nav-collapsed', '1');
    const { el } = shell();
    expect(el.querySelector('.frame')!.classList.contains('collapsed')).toBe(true);
  });

  it('navigates on Alt+digit, keyed on the code rather than the character', async () => {
    const { fixture } = shell();
    const router = TestBed.inject(Router);

    // Cancelled, or macOS inserts `¡` into whatever has focus.
    const event = altDigit('Digit3');
    await fixture.whenStable();
    expect(router.url).toBe('/runs');
    expect(event.defaultPrevented).toBe(true);

    altDigit('Digit4');
    await fixture.whenStable();
    expect(router.url).toBe('/videos');
  });

  it('needs the Alt key — a bare digit belongs to the page', async () => {
    const { fixture } = shell();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/runs');

    document.dispatchEvent(new KeyboardEvent('keydown', { code: 'Digit4', bubbles: true }));
    await fixture.whenStable();
    expect(router.url).toBe('/runs');

    // Cmd/Ctrl+digit is the browser's own tab switch; it is not ours to take.
    document.dispatchEvent(
      new KeyboardEvent('keydown', { code: 'Digit4', altKey: true, metaKey: true, bubbles: true }),
    );
    await fixture.whenStable();
    expect(router.url).toBe('/runs');
  });

  it('leaves a digit past the last section alone', async () => {
    const { fixture } = shell();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/runs');

    altDigit('Digit9');
    await fixture.whenStable();

    expect(router.url).toBe('/runs');
  });

  it('does not steal the keystroke from a text field', async () => {
    const { fixture, el } = shell();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/runs');

    const textarea = document.createElement('textarea');
    el.append(textarea);
    textarea.focus();
    textarea.dispatchEvent(
      new KeyboardEvent('keydown', { code: 'Digit1', altKey: true, bubbles: true }),
    );
    await fixture.whenStable();

    expect(router.url).toBe('/runs');
    textarea.remove();
  });

  it('shows the shortcut on every section, for AT and for hover', () => {
    const { el } = shell();
    const links = [...el.querySelectorAll('nav a')];

    expect(links.length).toBe(6);
    expect(links[0].getAttribute('aria-keyshortcuts')).toBe('Alt+1');
    // The rail no longer prints `01`…`06`, so the title is the only thing naming each shortcut.
    expect(links[5].getAttribute('aria-keyshortcuts')).toBe('Alt+6');
    expect(links[0].getAttribute('title')).toBe('Ingest (Alt+1)');
    expect(links[5].getAttribute('title')).toBe('Settings (Alt+6)');
  });
});
