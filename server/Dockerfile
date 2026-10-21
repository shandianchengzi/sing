FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu && pip install --no-cache-dir -r requirements.txt
RUN useradd --create-home --uid 10001 sing && mkdir -p /data /home/sing/.cache/torch && chown -R sing:sing /data /home/sing/.cache
COPY app.py .
USER sing
ENV SING_DATA=/data SING_DEVICE=cpu
EXPOSE 8765
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8765", "--workers", "1"]
