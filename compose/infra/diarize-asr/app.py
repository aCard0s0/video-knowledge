"""
diarize-asr — speaker-diarization HTTP sidecar for vidingest-server (M2).

Mirrors the shape of the existing Whisper ASR webservice: accept a multipart audio file,
run a model, return JSON. Wraps pyannote.audio 3.x.

Wire contract (consumed by `DiarizationClient.java`):

    POST /diarize?min_speakers=N&max_speakers=M
        Content-Type: multipart/form-data
        audio_file: <WAV bytes, 16kHz mono PCM>

    200 OK
    {
        "segments": [
            {"start": 0.0, "end": 4.2, "speaker": "SPEAKER_00"},
            ...
        ],
        "speakers": [
            {"label": "SPEAKER_00", "embedding": [<192 floats>]},
            ...
        ]
    }

Notes:
- HUGGINGFACE_TOKEN must be set; the pyannote pipeline downloads gated models on first run.
- The embedding field is optional: pyannote's pipeline does not return x-vectors by default.
  When unavailable, the field is omitted (or null) and the Java client treats it as such.
- CPU runs are slow (1.5-3x audio duration). Production deployments should use a GPU image.
"""

from __future__ import annotations

import logging
import os
import tempfile
import time
from typing import Optional

import torch
import uvicorn
from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from pyannote.audio import Pipeline

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("diarize-asr")

PIPELINE_NAME = os.environ.get("DIARIZATION_PIPELINE", "pyannote/speaker-diarization-3.1")
HF_TOKEN = os.environ.get("HUGGINGFACE_TOKEN", os.environ.get("HF_TOKEN"))

app = FastAPI(title="diarize-asr", version="0.1.0")
_pipeline: Optional[Pipeline] = None


def _load_pipeline() -> Pipeline:
    """Lazy-load the pyannote pipeline on first request to keep startup fast."""
    global _pipeline
    if _pipeline is not None:
        return _pipeline

    if not HF_TOKEN:
        raise RuntimeError(
            "HUGGINGFACE_TOKEN is not set; pyannote requires it to download "
            f"the gated model '{PIPELINE_NAME}'. Accept the model EULA on Hugging Face "
            "and set HUGGINGFACE_TOKEN in the sidecar environment."
        )

    log.info("Loading pyannote pipeline: %s", PIPELINE_NAME)
    t0 = time.monotonic()
    pipe = Pipeline.from_pretrained(PIPELINE_NAME, use_auth_token=HF_TOKEN)
    # GPU if available — pyannote falls back to CPU when CUDA isn't present.
    if torch.cuda.is_available():
        pipe.to(torch.device("cuda"))
        log.info("pyannote pipeline using CUDA")
    else:
        log.info("pyannote pipeline using CPU (slow on long audio)")
    log.info("pyannote pipeline ready in %.2fs", time.monotonic() - t0)
    _pipeline = pipe
    return pipe


@app.get("/health")
def health() -> dict:
    """Liveness/readiness probe — does not require the model to be loaded."""
    return {
        "status": "ok",
        "pipeline_loaded": _pipeline is not None,
        "pipeline": PIPELINE_NAME,
    }


@app.post("/diarize")
async def diarize(
    audio_file: UploadFile = File(...),
    min_speakers: Optional[int] = Query(None, ge=1, le=64),
    max_speakers: Optional[int] = Query(None, ge=1, le=64),
) -> dict:
    """
    Run pyannote diarization on the uploaded audio file. Returns the segments + unique
    speakers in the JSON shape consumed by `DiarizationClient.java`.
    """
    started = time.monotonic()
    pipe = _load_pipeline()

    # pyannote needs a file path, not a stream — stash the upload in a temp file.
    suffix = os.path.splitext(audio_file.filename or "audio.wav")[1] or ".wav"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=True) as tmp:
        chunk = await audio_file.read()
        if not chunk:
            raise HTTPException(status_code=400, detail="audio_file is empty")
        tmp.write(chunk)
        tmp.flush()

        kwargs: dict = {}
        if min_speakers is not None:
            kwargs["min_speakers"] = min_speakers
        if max_speakers is not None:
            kwargs["max_speakers"] = max_speakers

        log.info(
            "diarize start: filename=%s, bytes=%d, kwargs=%s",
            audio_file.filename,
            len(chunk),
            kwargs,
        )
        diarization = pipe(tmp.name, **kwargs)

    segments = []
    seen_labels: set[str] = set()
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        segments.append(
            {
                "start": float(turn.start),
                "end": float(turn.end),
                "speaker": str(speaker),
            }
        )
        seen_labels.add(str(speaker))

    # pyannote's speaker-diarization-3.1 pipeline does not expose per-speaker embeddings
    # via the public Annotation object. We return the label set without embeddings; the
    # Java client treats `embedding` as optional and stores null when absent.
    speakers = [{"label": label, "embedding": None} for label in sorted(seen_labels)]

    elapsed_ms = int((time.monotonic() - started) * 1000)
    log.info(
        "diarize done: segments=%d, speakers=%d, elapsed_ms=%d",
        len(segments),
        len(speakers),
        elapsed_ms,
    )
    return {"segments": segments, "speakers": speakers}


if __name__ == "__main__":
    uvicorn.run(
        app,
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "9001")),
        log_level=os.environ.get("LOG_LEVEL", "info").lower(),
    )
