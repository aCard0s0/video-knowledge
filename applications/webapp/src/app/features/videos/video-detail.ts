import { Component, ElementRef, computed, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  KnowledgeService,
  SpeakerDto,
  SpeakersService,
  VideoMultimodalService,
  VideoPhasesService,
  VideosService,
} from '../../api/generated';
import { KNOWLEDGE_TYPES, OPTIONAL_PHASES, OptionalPhase, statusVar } from '../../core/domain';
import { humanDuration, timecode } from '../../core/time';
import { ApiFailure, toApiFailure } from '../../core/problem';
import { API_V1 } from '../../core/api-base';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { syncQueryParams } from '../../core/url-state';

type Pane = 'transcript' | 'frames' | 'fused' | 'knowledge' | 'speakers';

const SEG_SIZE = 50;
const FRAME_SIZE = 25;
const FUSED_SIZE = 50;

@Component({
  selector: 'vk-video-detail',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem],
  templateUrl: './video-detail.html',
  styleUrl: './video-detail.scss',
})
export class VideoDetail {
  readonly videoId = input.required<string>();

  private readonly videos = inject(VideosService);
  private readonly artifacts = inject(VideoMultimodalService);
  private readonly knowledgeApi = inject(KnowledgeService);
  private readonly speakersApi = inject(SpeakersService);
  private readonly phases = inject(VideoPhasesService);

  private readonly player = viewChild<ElementRef<HTMLVideoElement>>('player');

  protected readonly pane = signal<Pane>('transcript');
  protected readonly page = signal(0);
  protected readonly knowledgeType = signal<string>('');
  protected readonly cursor = signal(0);

  protected readonly running = signal<string | null>(null);
  protected readonly lastRun = signal<string | null>(null);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly renaming = signal<string | null>(null);

  constructor() {
    syncQueryParams({ pane: this.pane, page: this.page, type: this.knowledgeType });
  }

  protected readonly detail = rxResource({
    params: () => ({ id: this.videoId() }),
    stream: ({ params }) => this.videos.getVideoDetail(params.id),
  });

  // Each pane fetches only while it is the visible one — undefined params keep a resource idle.
  protected readonly segments = rxResource({
    params: () => (this.pane() === 'transcript' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.videos.listTranscriptionSegments(params.id, params.page, SEG_SIZE),
  });

  protected readonly frames = rxResource({
    params: () => (this.pane() === 'frames' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.artifacts.ocrResultsByFramePage(params.id, params.page, FRAME_SIZE),
  });

  protected readonly fused = rxResource({
    params: () => (this.pane() === 'fused' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.artifacts.multimodalTimelinePage(params.id, params.page, FUSED_SIZE),
  });

  protected readonly knowledge = rxResource({
    params: () => (this.pane() === 'knowledge' ? { id: this.videoId(), type: this.knowledgeType() } : undefined),
    stream: ({ params }) => this.knowledgeApi.listVideoKnowledge(params.id, (params.type || undefined) as never),
  });

  protected readonly speakers = rxResource({
    params: () => (this.pane() === 'speakers' ? { id: this.videoId() } : undefined),
    stream: ({ params }) => this.speakersApi.listVideoSpeakers(params.id),
  });

  protected readonly panes: { key: Pane; label: string; count: () => number }[] = [
    { key: 'transcript', label: 'Transcript', count: () => this.counts()?.transcriptionSegments ?? 0 },
    { key: 'frames', label: 'OCR frames', count: () => this.counts()?.ocrFrames ?? 0 },
    { key: 'fused', label: 'Fused timeline', count: () => this.counts()?.multimodalSegments ?? 0 },
    { key: 'knowledge', label: 'Knowledge', count: () => this.counts()?.knowledgeUnits ?? 0 },
    { key: 'speakers', label: 'Speakers', count: () => this.counts()?.speakers ?? 0 },
  ];

  protected readonly optionalPhases = OPTIONAL_PHASES;
  protected readonly knowledgeTypes = KNOWLEDGE_TYPES;
  protected readonly statusVar = statusVar;
  protected readonly timecode = timecode;

  /**
   * `/videos/{id}/knowledge` has no paging, and a long video can produce hundreds of units. Render
   * a bounded slice and say so, rather than pretending the list is complete or silently truncating.
   * ponytail: a cap, not virtualization — add paging server-side if this becomes the main view.
   */
  protected readonly KNOWLEDGE_CAP = 200;
  protected readonly knowledgeAll = computed(() => this.knowledge.value() ?? []);
  protected readonly knowledgeShown = computed(() => this.knowledgeAll().slice(0, this.KNOWLEDGE_CAP));

  protected readonly video = computed(() => this.detail.value()?.video);
  protected readonly counts = computed(() => this.detail.value()?.counts);
  protected readonly transcription = computed(() => this.detail.value()?.transcription);

  // Media and artifact URLs are bound straight into <video>, <img> and <a>, bypassing the
  // generated client, so they carry the API prefix themselves.
  protected readonly fileUrl = computed(() => `${API_V1}/videos/${this.videoId()}/file`);
  protected readonly txtUrl = computed(() => `${API_V1}/videos/${this.videoId()}/transcription/whisper.txt`);
  protected readonly jsonUrl = computed(() => `${API_V1}/videos/${this.videoId()}/transcription/whisper.json`);

  protected readonly detailFailure = computed(() => {
    const err = this.detail.error();
    return err ? toApiFailure(err) : this.failure();
  });

  protected readonly paneFailure = computed(() => {
    const err = this.segments.error() ?? this.frames.error() ?? this.fused.error() ?? this.knowledge.error() ?? this.speakers.error();
    return err ? toApiFailure(err) : null;
  });

  protected frameUrl(frameId: string | undefined): string {
    return frameId ? `${API_V1}/frames/${frameId}/image` : '';
  }

  protected show(pane: Pane): void {
    this.pane.set(pane);
    this.page.set(0);
  }

  /** The player is the spine of this screen: every artifact row is a seek. */
  protected seek(seconds: number | null | undefined): void {
    const el = this.player()?.nativeElement;
    if (!el || seconds === null || seconds === undefined) return;
    el.currentTime = seconds;
    void el.play().catch(() => {
      // Autoplay refusals are the browser's call; the position is already set.
    });
  }

  protected onTime(): void {
    const el = this.player()?.nativeElement;
    if (el) this.cursor.set(el.currentTime);
  }

  protected active(start: number | null | undefined, end: number | null | undefined): boolean {
    if (start === null || start === undefined) return false;
    const now = this.cursor();
    return now >= start && now < (end ?? start + 0.5);
  }

  /**
   * Per-phase rerun is synchronous and idempotent server-side (each phase wipes and repopulates
   * its own artifacts), so the honest feedback is elapsed time and rows written.
   */
  protected runPhase(phase: OptionalPhase): void {
    this.running.set(phase);
    this.failure.set(null);
    this.lastRun.set(null);
    this.phases.runVideoPhase(this.videoId(), phase).subscribe({
      next: (result) => {
        this.running.set(null);
        this.lastRun.set(
          `${result.phase}: ${humanDuration(result.elapsedMs)}${
            result.rowsAffected === null || result.rowsAffected === undefined
              ? ''
              : `, ${result.rowsAffected} row(s)`
          }`,
        );
        this.detail.reload();
        this.reloadPane();
      },
      error: (err: unknown) => {
        this.running.set(null);
        this.failure.set(toApiFailure(err));
      },
    });
  }

  protected rename(speaker: SpeakerDto, displayName: string): void {
    if (!speaker.id) return;
    this.renaming.set(speaker.id);
    this.failure.set(null);
    this.speakersApi.renameSpeaker(speaker.id, { displayName }).subscribe({
      next: () => {
        this.renaming.set(null);
        this.speakers.reload();
      },
      error: (err: unknown) => {
        this.renaming.set(null);
        this.failure.set(toApiFailure(err));
      },
    });
  }

  private reloadPane(): void {
    const byPane: Record<Pane, { reload(): void }> = {
      transcript: this.segments,
      frames: this.frames,
      fused: this.fused,
      knowledge: this.knowledge,
      speakers: this.speakers,
    };
    byPane[this.pane()].reload();
  }
}
