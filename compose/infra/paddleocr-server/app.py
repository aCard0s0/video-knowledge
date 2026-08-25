"""
paddleocr-server — OCR HTTP sidecar for vidingest-server (M4).

Mirrors the shape of the existing whisper/diarize sidecars: accept a multipart image,
run PaddleOCR, return JSON. The wire contract is consumed by `PaddleOcrClient.java`:

    POST /ocr?lang=en
        Content-Type: multipart/form-data
        image: <JPG bytes>

    200 OK
    {
        "lines": [
            {
                "text": "Hello world",
                "confidence": 0.94,
                "bbox": [[10, 20], [110, 20], [110, 40], [10, 40]],
                "language": "en"
            },
            ...
        ]
    }

Notes:
- PaddleOCR auto-downloads models on first inference; we pre-warm at startup so the first
  /ocr call isn't cold. The model cache is mounted at /models/paddleocr via docker-compose.
- The Java side already applies a confidence floor (vidingest.ocr.min-confidence). We don't
  filter on the sidecar side so operators can tune the threshold without rebuilding the image.
- CPU is fine for the volumes we care about (a few hundred frames per video). GPU is a
  drop-in image swap when needed.
"""

from __future__ import annotations

import logging
import os
import time
from typing import Optional

import uvicorn
from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from paddleocr import PaddleOCR
from PIL import Image
from io import BytesIO
import numpy as np

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("paddleocr-server")

DEFAULT_LANG = os.environ.get("OCR_DEFAULT_LANG", "en")
USE_ANGLE_CLS = os.environ.get("OCR_USE_ANGLE_CLS", "true").lower() in ("1", "true", "yes")

app = FastAPI(title="paddleocr-server", version="0.1.0")

# One PaddleOCR engine per language. Loaded lazily and memoised so we don't pay the model-
# load cost on every request. {lang: PaddleOCR}
_engines: dict[str, PaddleOCR] = {}


def _engine(lang: str) -> PaddleOCR:
    """Lazy-load and cache a PaddleOCR engine for the given language code."""
    if lang in _engines:
        return _engines[lang]
    log.info("Loading PaddleOCR engine: lang=%s, use_angle_cls=%s", lang, USE_ANGLE_CLS)
    t0 = time.monotonic()
    engine = PaddleOCR(use_angle_cls=USE_ANGLE_CLS, lang=lang, show_log=False)
    log.info("PaddleOCR engine ready: lang=%s, elapsed=%.2fs", lang, time.monotonic() - t0)
    _engines[lang] = engine
    return engine


@app.on_event("startup")
async def warmup() -> None:
    """Pre-warm the default-language engine so the first request isn't cold."""
    try:
        _engine(DEFAULT_LANG)
    except Exception:  # noqa: BLE001
        # Don't block startup on warmup failure — the failure will resurface on the first
        # /ocr call with a clear error message.
        log.exception("PaddleOCR engine warmup failed; will retry lazily on first request")


@app.get("/health")
def health() -> dict:
    """Liveness/readiness probe. Reports which engines are warm."""
    return {
        "status": "ok",
        "default_lang": DEFAULT_LANG,
        "loaded_langs": sorted(_engines.keys()),
    }


@app.post("/ocr")
async def ocr(
    image: UploadFile = File(...),
    lang: Optional[str] = Query(None, description="Override the default OCR language."),
) -> dict:
    """Run OCR on the uploaded image and return one line per detection."""
    started = time.monotonic()
    chosen_lang = (lang or DEFAULT_LANG).lower()

    raw = await image.read()
    if not raw:
        raise HTTPException(status_code=400, detail="image is empty")

    # PaddleOCR accepts numpy arrays (HWC, RGB) or file paths. We decode the upload in
    # memory rather than touching disk — the Java side already wrote it once.
    try:
        pil = Image.open(BytesIO(raw)).convert("RGB")
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"image decode failed: {e}") from e
    arr = np.asarray(pil)

    try:
        engine = _engine(chosen_lang)
    except Exception as e:  # noqa: BLE001
        log.exception("Failed to load PaddleOCR engine for lang=%s", chosen_lang)
        raise HTTPException(status_code=503, detail=f"engine load failed: {e}") from e

    try:
        result = engine.ocr(arr, cls=USE_ANGLE_CLS)
    except Exception as e:  # noqa: BLE001
        log.exception("PaddleOCR inference failed: filename=%s", image.filename)
        raise HTTPException(status_code=500, detail=f"ocr failed: {e}") from e

    lines: list[dict] = []
    # PaddleOCR returns a list of pages; for a single image we get one page.
    for page in result or []:
        if not page:
            continue
        for detection in page:
            # detection = [bbox, (text, confidence)] in PaddleOCR ≥2.x
            try:
                bbox, (text, score) = detection
            except (TypeError, ValueError):
                continue
            if not text or not str(text).strip():
                continue
            lines.append(
                {
                    "text": str(text),
                    "confidence": float(score),
                    "bbox": [[float(x), float(y)] for x, y in bbox],
                    "language": chosen_lang,
                }
            )

    elapsed_ms = int((time.monotonic() - started) * 1000)
    log.info(
        "ocr done: filename=%s, lang=%s, bytes=%d, lines=%d, elapsed_ms=%d",
        image.filename,
        chosen_lang,
        len(raw),
        len(lines),
        elapsed_ms,
    )
    return {"lines": lines}


if __name__ == "__main__":
    uvicorn.run(
        app,
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8002")),
        log_level=os.environ.get("LOG_LEVEL", "info").lower(),
    )
