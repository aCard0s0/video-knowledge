import { HttpErrorResponse } from '@angular/common/http';

/**
 * RFC 9457 ProblemDetail — what `VidingestApiExceptionHandler` actually returns. The spec
 * documents no 4xx/5xx on any of the 39 operations and carries no error schema, so the generated
 * client types error bodies as `any` and this shape has to be declared by hand.
 *
 * Titles are a closed set: Bad request | Validation failed | Not found | Conflict |
 * Upstream failure | Internal error.
 */
export interface ProblemDetail {
  status?: number;
  title?: string;
  detail?: string;
  instance?: string;
  /** Present on "Validation failed" only: field name → message. */
  fields?: Record<string, string>;
}

export interface ApiFailure {
  status: number;
  title: string;
  detail: string;
  fields: { field: string; message: string }[];
  /** X-Correlation-Id — the grep key for the server log. */
  correlationId: string | null;
  instance: string | null;
}

/**
 * Never collapses into "something went wrong": status, title and detail are all preserved and
 * rendered separately, validation fields are kept as a list, and the correlation id survives
 * even when the body does not (it is a response header).
 */
export function toApiFailure(err: unknown): ApiFailure {
  if (!(err instanceof HttpErrorResponse)) {
    return {
      status: 0,
      title: 'Client error',
      detail: err instanceof Error ? err.message : String(err),
      fields: [],
      correlationId: null,
      instance: null,
    };
  }

  const correlationId = err.headers?.get('X-Correlation-Id') ?? null;

  // status 0 is the browser refusing or the server being down — no body to read.
  if (err.status === 0) {
    return {
      status: 0,
      title: 'Server unreachable',
      detail: 'No response from the VidIngest server. Check that it is running on port 8051.',
      fields: [],
      correlationId,
      instance: null,
    };
  }

  const body: ProblemDetail = typeof err.error === 'object' && err.error !== null ? err.error : {};
  const fields = Object.entries(body.fields ?? {}).map(([field, message]) => ({
    field,
    message: String(message),
  }));

  return {
    status: body.status ?? err.status,
    title: body.title ?? err.statusText ?? 'Request failed',
    detail: body.detail ?? (typeof err.error === 'string' ? err.error : err.message),
    fields,
    correlationId,
    instance: body.instance ?? null,
  };
}

/** Either a resource that may have errored, or a signal holding an already-translated failure. */
type FailureSource = { error: () => unknown } | (() => ApiFailure | null);

/**
 * The first failure worth showing, in the order given.
 *
 * `toApiFailure` was always the one translator, but *which* failure wins was decided once per
 * screen: seven hand-written computeds ORing resource errors together, under three different names,
 * and with two different rules — the ingest screen showed the submit error over a load error, every
 * other screen showed the opposite. Same operator, same question, a different answer per screen,
 * and a new screen inherited whichever rule its copy came from.
 *
 * The order here is the rule, and it is the same everywhere: **the action the operator just took
 * comes first**, then the loads behind it. A mutation failure is only ever set while it is the
 * newest thing that happened — every handler clears it before sending — so it cannot bury a load
 * error that outlives it.
 */
export function firstFailure(...sources: FailureSource[]): ApiFailure | null {
  for (const source of sources) {
    // A signal is callable; a ResourceRef is not.
    if (typeof source === 'function') {
      const failure = source();
      if (failure) return failure;
    } else {
      const err = source.error();
      if (err) return toApiFailure(err);
    }
  }
  return null;
}

/**
 * A resource's value, or undefined while it is loading *or* has errored.
 *
 * `ResourceRef.value()` **throws** `ResourceValueError` once the resource is in its error state,
 * so every read has to be gated. Reading one unguarded takes the whole template down with it: the
 * screen sits on its loading text and the error panel it already has never renders, because the
 * `@if` guarding it threw before the `@else if (r.error())` branch could be reached.
 *
 * Guard the read, and check `error()` *before* the value branch in the template.
 */
export function valueOf<T>(resource: { hasValue: () => boolean; value: () => T }): T | undefined {
  return resource.hasValue() ? resource.value() : undefined;
}
