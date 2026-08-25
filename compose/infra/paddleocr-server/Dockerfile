# paddleocr-server — OCR sidecar (PaddleOCR + FastAPI)
#
# CPU-only image by default. For GPU: swap base to nvidia/cuda:12.x-cudnn-runtime, install
# python3.11, and replace paddlepaddle with paddlepaddle-gpu in requirements.txt.
FROM python:3.11-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    # PaddleOCR caches downloaded models here; mounted as a volume in docker-compose so
    # restarts don't re-download.
    PADDLE_OCR_BASE_DIR=/models/paddleocr \
    PORT=8002 \
    OCR_DEFAULT_LANG=en

# libgomp1 is required by paddlepaddle at runtime. libsm6/libxext6/libgl1 cover the OpenCV
# stack PaddleOCR uses for preprocessing.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libgomp1 \
        libsm6 \
        libxext6 \
        libgl1 \
        ffmpeg \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --upgrade pip \
    && pip install -r /app/requirements.txt

COPY app.py /app/app.py

# Non-root user matching the platform convention (uid/gid 1000).
RUN useradd --create-home --uid 1000 paddleocr \
    && mkdir -p /models/paddleocr \
    && chown -R paddleocr:paddleocr /app /models/paddleocr
USER paddleocr

EXPOSE 8002
HEALTHCHECK --interval=30s --timeout=5s --retries=5 --start-period=30s \
    CMD curl -fsS http://localhost:8002/health || exit 1

CMD ["python", "-u", "app.py"]
