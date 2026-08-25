# diarize-asr — speaker-diarization sidecar (pyannote.audio 3.x + FastAPI)
#
# CPU-only image by default. For GPU support: bump the base to nvidia/cuda:12.x-cudnn-runtime,
# install python3.11, and swap the torch wheels to the cu121 index in requirements.txt.
#
# Required runtime env:
#   HUGGINGFACE_TOKEN  — pyannote/speaker-diarization-3.1 is gated; accept the EULA on HF
#                        and set the token here. Without it, the first /diarize call 500s.
FROM python:3.11-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    # Models cached here so re-pulls aren't required on every container restart.
    HF_HOME=/models/huggingface \
    PORT=9001

# ffmpeg is needed by torchaudio when decoding non-WAV inputs; libsndfile1 for WAV decoding.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --upgrade pip \
    && pip install -r /app/requirements.txt

COPY app.py /app/app.py

# Non-root user (uid/gid 1000) to match the rest of the platform images.
RUN useradd --create-home --uid 1000 diarize \
    && mkdir -p /models/huggingface \
    && chown -R diarize:diarize /app /models/huggingface
USER diarize

EXPOSE 9001
HEALTHCHECK --interval=30s --timeout=5s --retries=5 --start-period=20s \
    CMD curl -fsS http://localhost:9001/health || exit 1

CMD ["python", "-u", "app.py"]
