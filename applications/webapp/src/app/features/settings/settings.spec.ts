import { describe, expect, it } from 'vitest';

import { buildUpdate } from './settings';
import { ConnectionSummary, ConnectionSummaryNameEnum } from '../../api/generated';

const llm: ConnectionSummary = {
  name: ConnectionSummaryNameEnum.Knowledge,
  supportsBaseUrl: true,
  supportsModel: true,
  supportsEnabled: true,
};

const sidecar: ConnectionSummary = {
  name: ConnectionSummaryNameEnum.Ocr,
  supportsBaseUrl: true,
  supportsModel: false,
  supportsEnabled: true,
};

/** Local ffmpeg: a toggle and nothing else. */
const frames: ConnectionSummary = {
  name: ConnectionSummaryNameEnum.FrameSample,
  supportsBaseUrl: false,
  supportsModel: false,
  supportsEnabled: true,
};

const form = {
  provider: 'openai-compatible',
  baseUrl: '  http://host.docker.internal:8000/v1  ',
  model: 'Qwen2.5-14B-Instruct-4bit',
  apiKey: '',
  enabled: true,
};

/**
 * What this screen computes rather than renders. Every case below has a wrong version that looks
 * identical on the card, and one of them silently destroys a credential.
 */
describe('buildUpdate', () => {
  it('omits an untouched api key rather than sending an empty string', () => {
    // The box always starts empty because the server never returns the key. Sending `''` is the
    // server's "clear it", so saving a base-URL edit would wipe the stored credential.
    expect(buildUpdate(llm, form).apiKey).toBeUndefined();
  });

  it('sends a typed api key through as-is', () => {
    expect(buildUpdate(llm, { ...form, apiKey: 'sk-live' }).apiKey).toBe('sk-live');
  });

  it('trims the base URL, which is the field most likely to arrive with a stray space', () => {
    expect(buildUpdate(llm, form).baseUrl).toBe('http://host.docker.internal:8000/v1');
  });

  it('omits model and enabled for a connection that does not have them', () => {
    const body = buildUpdate(sidecar, { ...form, enabled: false });
    // The sidecar's model is fixed by its own image; sending one would store a value nothing reads.
    expect(body.model).toBeUndefined();
    // OCR *does* have a toggle, so this one is sent.
    expect(body.enabled).toBe(false);
  });

  it('omits the base URL for a connection that has no endpoint', () => {
    // The card renders no box, so the control still holds whatever the row seeded it with.
    // Sending that as a base URL would either save a value nothing reads, or — blank — 400.
    const body = buildUpdate(frames, { ...form, enabled: false });
    expect(body.baseUrl).toBeUndefined();
    expect(body.enabled).toBe(false);
  });

  it('omits enabled for a connection with no phase toggle', () => {
    const embeddings: ConnectionSummary = {
      name: ConnectionSummaryNameEnum.Embeddings,
      supportsBaseUrl: true,
      supportsModel: true,
      supportsEnabled: false,
    };
    expect(buildUpdate(embeddings, form).enabled).toBeUndefined();
  });

  it('omits a blank model rather than clearing the configured one', () => {
    expect(buildUpdate(llm, { ...form, model: '   ' }).model).toBeUndefined();
  });
});
