import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  ConnectionSummary,
  ConnectionTestResult,
  ConnectionsService,
  UpdateConnectionRequest,
} from '../../api/generated';
import { absoluteTime } from '../../core/time';
import { firstFailure, valueOf } from '../../core/problem';
import { actionState } from '../../core/action';
import { Icon } from '../../ui/icon';
import { Problem } from '../../ui/problem';

type ConnectionName = NonNullable<ConnectionSummary['name']>;

/** What the form holds. Exported for {@link buildUpdate}. */
export interface ConnectionFormValue {
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  enabled: boolean;
}

/**
 * Turn the card's form into the request body.
 *
 * Pulled out of the component because it is the part with rules rather than markup, and all of them
 * have a wrong version that looks identical on screen: an empty api-key box must be *omitted* (the
 * server reads `""` as "clear the stored key"), and a field the connection does not support must be
 * omitted rather than sent as an empty string the server would store.
 */
export function buildUpdate(
  row: ConnectionSummary,
  raw: ConnectionFormValue,
): UpdateConnectionRequest {
  return {
    provider: raw.provider,
    // FRAME_SAMPLE is local ffmpeg and has no endpoint. Sending `''` would be a blank base URL
    // the server rejects, so the card that has no box must send no field.
    baseUrl: row.supportsBaseUrl ? raw.baseUrl.trim() : undefined,
    model: row.supportsModel ? raw.model.trim() || undefined : undefined,
    apiKey: raw.apiKey === '' ? undefined : raw.apiKey,
    enabled: row.supportsEnabled ? raw.enabled : undefined,
  };
}

/**
 * One line under the provider select saying what the selected value actually reaches.
 *
 * `openai` and `openai-compatible` are two entries whose names do not distinguish them, and the
 * choice is not recoverable from anything else on the card — both take a base URL, a model and a
 * key. Picking wrong is a 400 during the next run, not a validation error here.
 *
 * This is **not** a mirror of `supportedProviders`: it gates nothing and enumerates nothing. An
 * unrecognised value renders no note, so a provider added server-side still appears in the
 * dropdown and still works, with one fewer sentence beside it.
 */
export function providerNote(provider: string | undefined): string | null {
  switch (provider) {
    case 'openai':
      return 'api.openai.com itself. Needs a key, and a base URL ending in /v1.';
    case 'openai-compatible':
      return 'Any server speaking /v1 — LM Studio, llama.cpp, mlx, vLLM, or a remote host.';
    case 'ollama':
      return "Ollama's native API. Not /v1 — the daemon root.";
    case 'disabled':
      return 'Embeddings off. Semantic search returns nothing while this is selected.';
    default:
      // The three local/sidecar providers, and anything added later. The card's own name already
      // says what they are.
      return null;
  }
}

/**
 * One line under the model box, for the cases where a valid-looking model id fails at run time.
 *
 * Same defensive shape as {@link providerNote} and the same reason for existing: none of these is
 * a value the server can reject on the way in, because each is a property of what the model
 * *does*, not of what the string *is*. The connections API validates the provider, never the
 * model — so without this the screen can reach each of these states silently.
 *
 * ponytail: written here rather than served as a `modelNote` field on `ConnectionSummary`. A stale
 * tip is a wrong sentence; a stale control would be a broken screen. Move it to the wire if the
 * list grows past a handful.
 */
export function modelNote(row: ConnectionSummary, provider: string | undefined): string | null {
  const openAi = provider === 'openai';
  switch (row.name) {
    case 'EMBEDDINGS':
      // True whatever the provider: the column is the constraint, not the vendor.
      return openAi
        ? 'Must be 1536-wide. text-embedding-3-small fits; -3-large needs truncation.'
        : 'Must produce 1536 values — the pgvector column is VECTOR(1536).';
    case 'TRANSCRIPTION':
      return openAi
        ? 'Only whisper-1 returns timed segments. gpt-4o-transcribe returns none.'
        : null;
    case 'KNOWLEDGE':
      return openAi
        ? 'A reasoning model spends the output cap on reasoning and can return nothing.'
        : null;
    default:
      return null;
  }
}

/** The form behind one card. Kept per connection so an unsaved edit survives a list reload. */
type ConnectionForm = FormGroup<{
  provider: FormControl<string>;
  baseUrl: FormControl<string>;
  model: FormControl<string>;
  apiKey: FormControl<string>;
  enabled: FormControl<boolean>;
}>;

/**
 * Settings — the connections to the model runtimes and the sidecars.
 *
 * The screen existed as a placeholder for the console's own preferences. This is the first thing
 * on it that is not a preference: the connections are server state, editable at runtime, and
 * the alternative to this screen is editing `.env` and recreating the container.
 *
 * FRAME_SAMPLE is on the list despite having no endpoint, because what an operator manages here is
 * which enrichment this deployment does — and its toggle is what makes OCR's mean anything.
 *
 * One card per connection rather than a table. Every row has five fields and three actions, and a
 * table of those is a horizontal scroll on any width where the card grid is still comfortable.
 *
 * Nothing here mirrors a server enum by hand: springdoc emits `ConnectionName` as a real enum
 * schema, and `supportedProviders` / `supportsBaseUrl` / `supportsModel` / `supportsEnabled` come
 * off the summary. A new connection or a new provider therefore shows up here with no client
 * change at all — FRAME_SAMPLE needed only the two `@if`s its missing endpoint implies, and
 * `openai` needed none.
 *
 * What it needed instead was prose. `openai` and `openai-compatible` sit in the same dropdown and
 * their names do not distinguish them; the api-key box called itself *optional* on a connection
 * that 401s without one; and three model ids that look valid here fail at run time for reasons the
 * server cannot check. {@link providerNote}, {@link apiKeyPlaceholder} and {@link modelNote} are
 * that, and all three answer null/neutral for anything they do not recognise — so they can go
 * stale into silence, never into a wrong control.
 */
@Component({
  selector: 'vk-settings',
  imports: [ReactiveFormsModule, Problem, Icon],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings {
  private readonly connections = inject(ConnectionsService);

  /**
   * Save, test and reset, keyed by connection, so only the card mid-request goes busy. The two-step
   * reset rides the same instance. This screen is where `actionState` was first extracted, as a
   * local `start`/`fail` pair; six screens carry the same four signals.
   */
  protected readonly action = actionState<ConnectionName>();
  protected readonly probes = signal<Record<string, ConnectionTestResult>>({});

  private readonly forms = new Map<ConnectionName, ConnectionForm>();

  protected readonly list = rxResource({
    stream: () => this.connections.listConnections(),
  });

  protected readonly rows = computed(() => valueOf(this.list) ?? []);
  protected readonly failure = computed(() => this.action.failure() ?? firstFailure(this.list));

  protected readonly absoluteTime = absoluteTime;

  /**
   * The card's form, created on first render and then kept.
   *
   * Rebuilding it from the row on every change detection would throw away whatever the operator
   * has typed the moment anything reloads the list — which saving and probing both do.
   */
  protected form(row: ConnectionSummary): ConnectionForm {
    const name = row.name!;
    let form = this.forms.get(name);
    if (!form) {
      form = new FormGroup({
        provider: new FormControl(row.provider ?? '', { nonNullable: true }),
        baseUrl: new FormControl(row.baseUrl ?? '', {
          nonNullable: true,
          validators: row.supportsBaseUrl ? [Validators.required] : [],
        }),
        model: new FormControl(row.model ?? '', { nonNullable: true }),
        // Never populated from the server — it is never sent. Empty means "keep what is stored".
        apiKey: new FormControl('', { nonNullable: true }),
        enabled: new FormControl(row.enabled ?? true, { nonNullable: true }),
      });
      this.forms.set(name, form);
    }
    return form;
  }

  protected probe(row: ConnectionSummary): ConnectionTestResult | undefined {
    return this.probes()[row.name!];
  }

  protected readonly providerNote = providerNote;
  protected readonly modelNote = modelNote;

  /**
   * "optional" was a claim, and on `openai` it is false — api.openai.com 401s without a key, and
   * the probe would report that as an unreachable host before this said anything.
   */
  protected apiKeyPlaceholder(row: ConnectionSummary, provider: string | undefined): string {
    if (row.hasApiKey) {
      return '•••••••• stored — leave blank to keep';
    }
    return provider === 'openai' ? 'api key (required)' : 'api key (optional)';
  }

  protected save(row: ConnectionSummary): void {
    const name = row.name!;
    const form = this.form(row);
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    const body = buildUpdate(row, form.getRawValue());

    this.action.start(name);
    this.connections.updateConnection(name, body).subscribe({
      next: (saved) => {
        // The key box is cleared because its value now lives on the server and cannot come back.
        form.controls.apiKey.setValue('');
        // Nothing on the card necessarily changes — saving the value that was already there is a
        // no-op on screen — so the confirmation has to be said rather than shown. A card with no
        // endpoint would otherwise read "at null", which is the one thing it must not say.
        this.action.ok(
          saved.supportsBaseUrl
            ? `Saved ${name}. Now ${saved.provider} at ${saved.baseUrl}.`
            : `Saved ${name}. Phase ${saved.enabled ? 'enabled' : 'disabled'}.`,
        );
        this.list.reload();
      },
      error: (err: unknown) => this.action.fail(err),
    });
  }

  protected test(row: ConnectionSummary): void {
    const name = row.name!;
    this.action.start(name);
    this.connections.testConnection(name).subscribe({
      // A failed probe is a successful request: the answer is in the body, not in the error path.
      next: (result) => {
        this.probes.update((all) => ({ ...all, [name]: result }));
        this.action.ok(
          result.reachable
            ? `${name} answered in ${result.latencyMs}ms.`
            : `${name} did not answer: ${result.error}`,
        );
      },
      error: (err: unknown) => this.action.fail(err),
    });
  }

  /**
   * Drop the override and go back to what the environment configured.
   *
   * Confirmed because it is not undoable from this screen: the value it discards is whatever was
   * typed here, and the value it restores is one the console never displayed.
   */
  protected reset(row: ConnectionSummary): void {
    const name = row.name!;
    const warning = `Press Confirm reset to drop the stored override for ${name} and go back to the value the server started with.`;
    if (!this.action.confirm(name, warning)) return;

    this.action.start(name);
    this.connections.resetConnection(name).subscribe({
      next: () => {
        // The card has to be rebuilt from the restored values, so drop the form with the row.
        this.forms.delete(name);
        this.probes.update((all) => {
          const { [name]: _dropped, ...rest } = all;
          return rest;
        });
        this.action.ok(`Reset ${name} to its configured value.`);
        this.list.reload();
      },
      error: (err: unknown) => this.action.fail(err),
    });
  }
}
