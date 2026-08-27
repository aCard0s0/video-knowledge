import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { PhasePicker } from './phase-picker';
import { PipelinesService } from '../api/generated';
import { OPTIONAL_PHASES } from '../core/domain';

/**
 * A ticked chip claims the phase will run. These are the two ways that used to be false with the
 * chip still ticked — and the lane afterwards drew both as "turned off for this run".
 */
@Component({
  selector: 'vk-test-host',
  imports: [PhasePicker],
  template: '<vk-phase-picker [(skipped)]="skipped" />',
})
class Host {
  readonly skipped = signal<string[]>([]);
}

/**
 * Which phases the deployment runs arrives through the shared `Capabilities` singleton, which
 * reads `GET /pipelines/capabilities` — so the stub is the pipelines client, not an input.
 */
function picker(disabled: string[] = []) {
  const enabledPhases = OPTIONAL_PHASES.filter((p) => !disabled.includes(p));
  TestBed.configureTestingModule({
    providers: [
      {
        provide: PipelinesService,
        useValue: { getPipelineCapabilities: () => of({ enabledPhases, channelSyncLimit: 50 }) },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  TestBed.tick();
  const el = fixture.nativeElement as HTMLElement;
  const chip = (phase: string) =>
    [...el.querySelectorAll('label.chip')].find(
      (l) => l.textContent?.trim() === phase,
    ) as HTMLLabelElement;
  return { fixture, el, chip, host: fixture.componentInstance };
}

describe('PhasePicker', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('ticks every optional phase when the deployment runs them all', () => {
    const { el } = picker();
    const boxes = [...el.querySelectorAll<HTMLInputElement>('input')];
    expect(boxes).toHaveLength(7);
    expect(boxes.every((b) => b.checked && !b.disabled)).toBe(true);
  });

  it('marks a server-disabled phase unavailable rather than ticked', () => {
    const { chip } = picker(['OCR', 'KNOWLEDGE']);

    const ocr = chip('OCR');
    expect(ocr.classList.contains('unavailable')).toBe(true);
    expect(ocr.querySelector('input')!.checked).toBe(false);
    expect(ocr.querySelector('input')!.disabled).toBe(true);
    expect(ocr.title).toContain('disabled on this server');
    expect(chip('TRANSCRIBE').classList.contains('unavailable')).toBe(false);
  });

  /**
   * The server skips a dependent phase silently, so the picker offering OCR beside a FRAME_SAMPLE
   * the operator had just unticked was the same lie one gate over.
   */
  it('marks a phase unavailable once the phase it needs stops running', () => {
    const { chip, host } = picker();
    expect(chip('OCR').classList.contains('unavailable')).toBe(false);

    chip('FRAME_SAMPLE').querySelector('input')!.click();
    TestBed.tick();

    expect(host.skipped()).toEqual(['FRAME_SAMPLE']);
    expect(chip('OCR').title).toBe('OCR needs FRAME_SAMPLE, which is not running');
    expect(chip('DIARIZE').classList.contains('unavailable')).toBe(false);
  });

  it('carries a server-disabled upstream down to what depends on it', () => {
    const { chip } = picker(['TRANSCRIBE']);
    expect(chip('DIARIZE').title).toBe('DIARIZE needs TRANSCRIBE, which is not running');
  });

  /**
   * `skipPhases` records what the *operator* chose. Adding a server-disabled phase to it would make
   * the run claim they had skipped it, and a retry would then inherit that as a deliberate opt-out.
   */
  it('never puts an unavailable phase into skipPhases', () => {
    const { chip, host } = picker(['OCR']);

    chip('OCR').querySelector('input')!.click();
    TestBed.tick();

    expect(host.skipped()).toEqual([]);
  });
});
