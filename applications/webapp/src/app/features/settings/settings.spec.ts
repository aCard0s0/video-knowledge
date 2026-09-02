import { describe, expect, it } from 'vitest';

import { buildUpdate, modelNote, providerNote } from './settings';
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

/**
 * The three sentences the card says about a provider. Each exists because the server cannot answer
 * the question: `supportedProviders` names the values but not what they reach, and the connections
 * API validates the provider and never the model.
 */
describe('providerNote', () => {
  it('distinguishes openai from openai-compatible, which the dropdown alone does not', () => {
    // The whole reason the note exists: two entries whose names do not separate them.
    expect(providerNote('openai')).toContain('api.openai.com');
    expect(providerNote('openai-compatible')).not.toContain('api.openai.com');
  });

  it('says nothing for a provider it does not know', () => {
    // A value added server-side still renders and still works — it just loses its sentence.
    // Failing loudly here would be a console that breaks when the server gains a feature.
    expect(providerNote('some-future-runtime')).toBeNull();
    expect(providerNote(undefined)).toBeNull();
    // The local/sidecar providers carry no note; the card's own name already says what they are.
    expect(providerNote('ffmpeg')).toBeNull();
  });
});

describe('modelNote', () => {
  const embeddings: ConnectionSummary = { name: ConnectionSummaryNameEnum.Embeddings };
  const transcription: ConnectionSummary = { name: ConnectionSummaryNameEnum.Transcription };

  it('warns about the column width on embeddings whatever the provider', () => {
    // VECTOR(1536) is the constraint, not the vendor — so this one is not gated on openai.
    expect(modelNote(embeddings, 'ollama')).toContain('1536');
    expect(modelNote(embeddings, 'openai')).toContain('1536');
  });

  it('names whisper-1 only on openai, where it is the only model that returns segments', () => {
    expect(modelNote(transcription, 'openai')).toContain('whisper-1');
    // A local runtime serves whisper-large-v3-turbo; the note would be actively wrong there.
    expect(modelNote(transcription, 'openai-compatible')).toBeNull();
  });

  it('warns that the output cap covers reasoning tokens on openai knowledge', () => {
    expect(modelNote(llm, 'openai')).toContain('reasoning');
    expect(modelNote(llm, 'ollama')).toBeNull();
  });

  it('says nothing for a connection with no model of its own', () => {
    expect(modelNote(sidecar, 'openai')).toBeNull();
    expect(modelNote(frames, 'openai')).toBeNull();
  });
});
