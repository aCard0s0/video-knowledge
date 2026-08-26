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
