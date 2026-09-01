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
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
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
 * change at all — FRAME_SAMPLE needed only the two `@if`s its missing endpoint implies.
 */
@Component({
  selector: 'vk-settings',
  imports: [ReactiveFormsModule, Problem, Icon],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings {
  private readonly connections = inject(ConnectionsService);

  /** Which card is mid-request, so only that one's buttons go busy. */
  protected readonly busy = signal<ConnectionName | null>(null);
  /** Two-step reset: the first press arms the card, the second sends it. */
  protected readonly armed = signal<ConnectionName | null>(null);
  /** What the last press did, for the `role="status"` line. Empty is the resting state. */
  protected readonly said = signal('');
  protected readonly probes = signal<Record<string, ConnectionTestResult>>({});
  private readonly actionFailure = signal<ApiFailure | null>(null);

  private readonly forms = new Map<ConnectionName, ConnectionForm>();

  protected readonly list = rxResource({
    stream: () => this.connections.listConnections(),
  });

  protected readonly rows = computed(() => valueOf(this.list) ?? []);
  protected readonly failure = computed(() => this.actionFailure() ?? firstFailure(this.list));

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

  protected apiKeyPlaceholder(row: ConnectionSummary): string {
    return row.hasApiKey ? '•••••••• stored — leave blank to keep' : 'api key (optional)';
  }

  protected save(row: ConnectionSummary): void {
    const name = row.name!;
    const form = this.form(row);
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    const body = buildUpdate(row, form.getRawValue());

    this.start(name);
    this.connections.updateConnection(name, body).subscribe({
      next: (saved) => {
        this.busy.set(null);
        // The key box is cleared because its value now lives on the server and cannot come back.
        form.controls.apiKey.setValue('');
        // Nothing on the card necessarily changes — saving the value that was already there is a
        // no-op on screen — so the confirmation has to be said rather than shown. A card with no
        // endpoint would otherwise read "at null", which is the one thing it must not say.
        this.said.set(
          saved.supportsBaseUrl
            ? `Saved ${name}. Now ${saved.provider} at ${saved.baseUrl}.`
            : `Saved ${name}. Phase ${saved.enabled ? 'enabled' : 'disabled'}.`,
        );
        this.list.reload();
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  protected test(row: ConnectionSummary): void {
    const name = row.name!;
    this.start(name);
    this.connections.testConnection(name).subscribe({
      // A failed probe is a successful request: the answer is in the body, not in the error path.
      next: (result) => {
        this.busy.set(null);
        this.probes.update((all) => ({ ...all, [name]: result }));
        this.said.set(
          result.reachable
            ? `${name} answered in ${result.latencyMs}ms.`
            : `${name} did not answer: ${result.error}`,
        );
      },
      error: (err: unknown) => this.fail(err),
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
    if (this.armed() !== name) {
      this.armed.set(name);
      this.said.set(
        `Press Confirm reset to drop the stored override for ${name} and go back to the value the server started with.`,
      );
      return;
    }
    this.armed.set(null);
    this.start(name);
    this.connections.resetConnection(name).subscribe({
      next: () => {
        this.busy.set(null);
        // The card has to be rebuilt from the restored values, so drop the form with the row.
        this.forms.delete(name);
        this.probes.update((all) => {
          const { [name]: _dropped, ...rest } = all;
          return rest;
        });
        this.said.set(`Reset ${name} to its configured value.`);
        this.list.reload();
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  private start(name: ConnectionName): void {
    this.busy.set(name);
    this.armed.set(null);
    this.actionFailure.set(null);
    this.said.set('');
  }

  private fail(err: unknown): void {
    this.busy.set(null);
    this.said.set('');
    this.actionFailure.set(toApiFailure(err));
  }
}
