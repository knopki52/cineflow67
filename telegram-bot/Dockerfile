FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 TZ=Europe/Moscow
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core libglib2.0-0 tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY bot ./bot
CMD ["python", "-m", "bot.main"]
