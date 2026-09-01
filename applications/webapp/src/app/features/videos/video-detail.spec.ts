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
import { SpeakerDto } from '../../api/generated';
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
  /** What `GET /videos/{id}/speakers` answers. */
  speakers?: unknown[];
};

/**
 * Routed, not bare: `videoId` arrives through `withComponentInputBinding` and the pane and type
 * arrive through `syncQueryParams`, which reads the real `ActivatedRoute` snapshot. Creating the
 * component directly leaves both empty, so every knowledge assertion would run against the
 * transcript pane of an unnamed video — which is how the first draft of this file passed nothing.
 */
@Component({ selector: 'vk-test-host', imports: [RouterOutlet], template: '<router-outlet />' })
class Host {}

async function screen({ disabled = [], units = [], query = {}, speakers = [] }: Options = {}) {
  const runVideoPhase = vi.fn(() => new Observable(() => {}));
  const listVideoSpeakers = vi.fn(() => of(speakers));
  const renameSpeaker = vi.fn((id: string, body: { displayName: string }) =>
    of({ ...(speakers as SpeakerDto[]).find((s) => s.id === id), displayName: body.displayName }),
  );
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
      { provide: SpeakersService, useValue: { listVideoSpeakers, renameSpeaker } },
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
  return { el, chip, tick, runVideoPhase, listVideoSpeakers, renameSpeaker };
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

describe('video detail: knowledge units read in timeline order', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /**
   * The server sends `createdAt ASC`, which is *insert* order: one batch writes its units in the
   * order the model emitted them, all sharing a timestamp. Rendered down a timecode gutter beside a
   * player, that list walks backwards — clicking straight down it seeks the player to 00:50, then
   * 01:15, then 00:00.
   */
  it('sorts by where in the video the unit is, not by when it was written', async () => {
    const { el } = await screen({
      units: [
        { id: 'a', type: 'CLAIM', title: 'third', content: '…', startSeconds: 110 },
        { id: 'b', type: 'TOPIC', title: 'first', content: '…', startSeconds: 0 },
        { id: 'c', type: 'ENTITY', title: 'second', content: '…', startSeconds: 35 },
      ],
    });

    const titles = [...el.querySelectorAll('.units .line')].map((n) => n.textContent!.trim());
    expect(titles).toEqual(['TOPIC first', 'ENTITY second', 'CLAIM third']);
  });

  it('puts a unit with no timecode last rather than at the front', async () => {
    const { el } = await screen({
      units: [
        { id: 'a', type: 'SUMMARY', title: 'whole video', content: '…' },
        { id: 'b', type: 'TOPIC', title: 'at the start', content: '…', startSeconds: 0 },
      ],
    });

    const titles = [...el.querySelectorAll('.units .line')].map((n) => n.textContent!.trim());
    expect(titles).toEqual(['TOPIC at the start', 'SUMMARY whole video']);
  });
});

describe('video detail: a PROCEDURE unit keeps its steps on separate lines', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /**
   * A PROCEDURE's content is numbered `WHEN … THEN …` steps separated by newlines — the one unit
   * type whose body is not a paragraph. The template binds it into a `<span class="body">`, which
   * needs `white-space: pre-wrap` in the SCSS to render them as lines; SCSS is not applied in this
   * runner, so what is asserted here is the half a test can hold: the newlines survive into the
   * DOM. A truncation pipe, a `.slice()`, or a `replace(/\s+/g, ' ')` added later would break
   * this, and those are the changes that would silently flatten the method back into prose.
   */
  it('binds the content unmodified, newlines and all', async () => {
    const content = [
      '1. WHEN a trend is identified THEN look for a low, high, lower low.',
      '2. WHEN liquidity is swept THEN wait for a shift of structure.',
      'Stop/abort: above the high.',
    ].join('\n');

    const { el } = await screen({
      units: [{ id: 'p1', type: 'PROCEDURE', title: 'A method', content, startSeconds: 0 }],
    });

    const body = el.querySelector('.units .body')!;
    expect(body.textContent).toContain('\n');
    expect(body.textContent!.split('\n').map((l) => l.trim()).filter(Boolean)).toHaveLength(3);
    expect(body.textContent).toContain('1. WHEN a trend is identified');
    expect(body.textContent).toContain('Stop/abort: above the high.');
  });

  /**
   * PROCEDURE must be offered by the type filter. The list is hand-mirrored from a server enum in
   * `core/domain.ts`, so a new constant renders as an unknown value until that file is updated —
   * nothing fails, the chip is simply missing.
   */
  it('offers PROCEDURE in the type filter', async () => {
    const { el } = await screen({ units: [UNIT] });

    const chips = [...el.querySelectorAll('.chips-row .btn-sm')].map((n) => n.textContent!.trim());
    expect(chips).toContain('PROCEDURE');
  });
});

describe('video detail: renaming a speaker leaves the other rows alone', () => {
  beforeEach(() => TestBed.resetTestingModule());

  const TWO = [
    { id: 's1', videoId: VIDEO_ID, label: 'SPEAKER_00', displayName: '', segmentCount: 18 },
    { id: 's2', videoId: VIDEO_ID, label: 'SPEAKER_01', displayName: '', segmentCount: 31 },
  ];

  /**
   * The regression this pins. Rows bind `[value]` with no `(input)` and `track speaker.id` keeps
   * the DOM node, so a `speakers.reload()` after a save re-evaluated that binding on *every* row
   * and reset whatever the operator had typed into the others. Two speakers is the smallest case
   * that shows it, and two speakers is the common case.
   */
  it('does not reset a sibling row the operator is still typing into', async () => {
    const { el, tick, listVideoSpeakers } = await screen({
      query: { pane: 'speakers' },
      speakers: TWO,
    });

    const inputs = [...el.querySelectorAll<HTMLInputElement>('table.grid tbody input')];
    inputs[1].value = 'Guest, unsaved';
    inputs[0].value = 'Host';
    el.querySelectorAll<HTMLButtonElement>('table.grid tbody button')[0].click();
    tick();

    expect(inputs[1].value).toBe('Guest, unsaved');
    // One request, not two: the PATCH answers with the updated row, so there is nothing to refetch.
    expect(listVideoSpeakers).toHaveBeenCalledTimes(1);
  });

  it('says what it saved, in a region that was already in the tree', async () => {
    const { el, tick } = await screen({ query: { pane: 'speakers' }, speakers: TWO });

    const region = el.querySelector('.rename-result')!;
    expect(region.getAttribute('role')).toBe('status');
    expect(getComputedStyle(region).display).not.toBe('none');

    el.querySelector<HTMLInputElement>('table.grid tbody input')!.value = 'Host';
    el.querySelectorAll<HTMLButtonElement>('table.grid tbody button')[0].click();
    tick();

    expect(region.textContent).toContain('SPEAKER_00');
    expect(region.textContent).toContain('Host');
  });

  it('reports a cleared name as cleared rather than as a save of nothing', async () => {
    const { el, tick } = await screen({
      query: { pane: 'speakers' },
      speakers: [{ ...TWO[0], displayName: 'Host' }],
    });

    el.querySelector<HTMLInputElement>('table.grid tbody input')!.value = '';
    el.querySelector<HTMLButtonElement>('table.grid tbody button')!.click();
    tick();

    expect(el.querySelector('.rename-result')!.textContent).toContain('Cleared');
  });
});
