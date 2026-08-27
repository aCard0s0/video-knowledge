import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, RouterOutlet, provideRouter, withComponentInputBinding } from '@angular/router';
import { Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { VideoDetail } from './video-detail';
import {
  KnowledgeService,
  PipelinesService,
  SpeakersService,
  VideoMultimodalService,
  VideoPhasesService,
  VideosService,
} from '../../api/generated';
import { OPTIONAL_PHASES } from '../../core/domain';

const VIDEO_ID = '11111111-2222-3333-4444-555555555555';

const DETAIL = {
  video: { id: VIDEO_ID, title: 'A video', status: 'COMPLETED', source: 'youtube', sourceVideoId: 'x' },
  counts: { transcriptionSegments: 0, speakers: 0, ocrFrames: 0, multimodalSegments: 0, knowledgeUnits: 4 },
  transcription: { present: true, status: 'COMPLETED', provider: 'whisper', language: 'en' },
};

const UNIT = { id: 'k1', type: 'SUMMARY', title: 'A unit', content: '…', startSeconds: 0 };

type Options = {
  /** Phases the deployment has switched off — what `GET /pipelines/capabilities` leaves out. */
  disabled?: string[];
  /** What `GET /videos/{id}/knowledge` answers for the type currently filtered on. */
  units?: unknown[];
  /** Query params the screen opens with, e.g. the knowledge type filter. */
  query?: Record<string, string>;
};

/**
 * Routed, not bare: `videoId` arrives through `withComponentInputBinding` and the pane and type
 * arrive through `syncQueryParams`, which reads the real `ActivatedRoute` snapshot. Creating the
 * component directly leaves both empty, so every knowledge assertion would run against the
 * transcript pane of an unnamed video — which is how the first draft of this file passed nothing.
 */
@Component({ selector: 'vk-test-host', imports: [RouterOutlet], template: '<router-outlet />' })
class Host {}

async function screen({ disabled = [], units = [], query = {} }: Options = {}) {
  const runVideoPhase = vi.fn(() => new Observable(() => {}));
  const enabledPhases = OPTIONAL_PHASES.filter((p) => !disabled.includes(p));

  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [{ path: 'videos/:videoId', component: VideoDetail }],
        withComponentInputBinding(),
      ),
      provideLocationMocks(),
      {
        provide: PipelinesService,
        useValue: { getPipelineCapabilities: () => of({ enabledPhases, channelSyncLimit: 200 }) },
      },
      {
        provide: VideosService,
        useValue: {
          getVideoDetail: () => of(DETAIL),
          listTranscriptionSegments: () => of({ items: [], page: 0, size: 50, total: 0 }),
        },
      },
      {
        provide: VideoMultimodalService,
        useValue: {
          ocrResultsByFramePage: () => of({ items: [], page: 0, size: 25, total: 0 }),
          multimodalTimelinePage: () => of({ items: [], page: 0, size: 50, total: 0 }),
        },
      },
      { provide: KnowledgeService, useValue: { listVideoKnowledge: () => of(units) } },
      { provide: SpeakersService, useValue: { listVideoSpeakers: () => of([]) } },
      { provide: VideoPhasesService, useValue: { runVideoPhase } },
    ],
  });

  const fixture = TestBed.createComponent(Host);
  const params = new URLSearchParams({ pane: 'knowledge', ...query });
  await TestBed.inject(Router).navigateByUrl(`/videos/${VIDEO_ID}?${params}`);
  TestBed.tick();

  const el = fixture.nativeElement as HTMLElement;
  const chip = (phase: string) =>
    [...el.querySelectorAll<HTMLButtonElement>('.phase-buttons button')].find((b) =>
      b.textContent?.includes(phase),
    )!;
  const tick = () => TestBed.tick();
  return { el, chip, tick, runVideoPhase };
}

describe('video detail: the knowledge pane distinguishes a filter from a video', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('names the type when the filter matched nothing, and offers to clear it', async () => {
    const { el } = await screen({ query: { type: 'QUESTION' }, units: [] });

    const empty = el.querySelector('vk-empty')!;
    expect(empty.textContent).toContain('No QUESTION units');
    expect(empty.textContent).toContain('Show all types');
    // The bug this pins: a filter with no matches used to offer an LLM extraction over the whole
    // video as its fix, while the tab beside it went on counting the units of other types.
    expect(empty.textContent).not.toContain('Run KNOWLEDGE');
  });

  it('offers the phase only when the video itself has none', async () => {
    const { el } = await screen({ units: [] });

    expect(el.querySelector('vk-empty')!.textContent).toContain('Run KNOWLEDGE');
  });

  it('renders the units rather than an empty state when the filter matched', async () => {
    const { el } = await screen({ query: { type: 'SUMMARY' }, units: [UNIT] });

    expect(el.querySelector('vk-empty')).toBeNull();
    expect(el.querySelector('.units')!.textContent).toContain('A unit');
  });
});

describe('video detail: a rerun the server will refuse is not offered', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('disables KNOWLEDGE when the deployment has it off, and says why', async () => {
    const { el, chip } = await screen({ disabled: ['KNOWLEDGE'] });

    expect(chip('KNOWLEDGE').disabled).toBe(true);
    expect(el.querySelector('.note.warn')!.textContent).toContain('KNOWLEDGE is off on this server');
    expect(el.querySelector('vk-empty')!.textContent).not.toContain('Run KNOWLEDGE');
  });

  /**
   * The half that is easy to over-fix. `VideoPhaseRunnerService` deliberately skips
   * `applies(ctx)` so a rerun forces the phase past the deployment toggle — that is the escape
   * hatch the row exists for ("re-OCR after a paddleocr-server upgrade"). Only
   * `KnowledgeExtractionService` gates on its own switch, so only KNOWLEDGE can be refused.
   * Measured against a server with OCR and FRAME_SAMPLE off: FRAME_SAMPLE answered 200 with 17
   * rows, KNOWLEDGE answered 409.
   */
  it('leaves every other server-disabled phase pressable', async () => {
    const { chip } = await screen({ disabled: ['DIARIZE', 'FRAME_SAMPLE', 'OCR', 'KNOWLEDGE'] });

    for (const phase of ['DIARIZE', 'FRAME_SAMPLE', 'OCR']) {
      expect(chip(phase).disabled, `${phase} should still be pressable`).toBe(false);
    }
  });

  it('leaves every phase pressable while the capabilities answer is still unknown', async () => {
    const { chip } = await screen();

    expect(OPTIONAL_PHASES.every((p) => !chip(p).disabled)).toBe(true);
  });
});

describe('video detail: a rerun takes two presses', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('arms on the first press and sends nothing', async () => {
    const { chip, tick, runVideoPhase } = await screen();

    chip('OCR').click();
    tick();

    expect(runVideoPhase).not.toHaveBeenCalled();
    expect(chip('OCR').textContent).toContain('re-run OCR?');
  });

  it('sends on the second press', async () => {
    const { chip, tick, runVideoPhase } = await screen();

    chip('OCR').click();
    tick();
    chip('OCR').click();
    tick();

    expect(runVideoPhase).toHaveBeenCalledWith(VIDEO_ID, 'OCR');
  });

  it('re-arms rather than firing when a different chip is pressed', async () => {
    const { chip, tick, runVideoPhase } = await screen();

    chip('OCR').click();
    tick();
    chip('FUSE').click();
    tick();

    expect(runVideoPhase).not.toHaveBeenCalled();
    expect(chip('OCR').textContent).not.toContain('re-run');
    expect(chip('FUSE').textContent).toContain('re-run FUSE?');
  });

  /**
   * The empty-state CTAs stay one press on purpose: an empty pane has nothing to wipe, and making
   * the operator confirm a rerun that destroys nothing is the kind of prompt people learn to click
   * through — which is what would blunt the confirm on the row that does destroy something.
   */
  it('fires the empty-state button on one press', async () => {
    const { el, tick, runVideoPhase } = await screen({ units: [] });

    el.querySelector<HTMLButtonElement>('vk-empty .btn-primary')!.click();
    tick();

    expect(runVideoPhase).toHaveBeenCalledWith(VIDEO_ID, 'KNOWLEDGE');
  });
});

describe('video detail: the rerun status line is a live region', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /**
   * `role="status"` has to be in the accessibility tree *before* it has something to say — an
   * `aria-live` node inserted at announce time is not reliably announced. It used to be
   * `display: none` while empty, which is the same thing one property over.
   */
  it('is present and never hidden while idle', async () => {
    const { el } = await screen();

    const region = el.querySelector('.run-result')!;
    expect(region.getAttribute('role')).toBe('status');
    expect(getComputedStyle(region).display).not.toBe('none');
  });

  it('reports the phase and its elapsed time while the request is open', async () => {
    const { el, chip, tick } = await screen();

    chip('OCR').click();
    tick();
    chip('OCR').click();
    tick();

    // The stub never answers, which is the case this line exists for: OCR over seventeen frames
    // measured past ten minutes with nothing but a 10px ellipsis to say the request was still open.
    expect(el.querySelector('.run-result')!.textContent).toContain('OCR running');
    expect(el.querySelector('.run-result')!.textContent).toContain('elapsed');
  });
});
