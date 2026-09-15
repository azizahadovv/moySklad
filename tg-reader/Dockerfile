# 📨 tg-reader — Telethon (Python) userbot xizmati. TDLib/Java (src/tdlib/java) o'rnini bosadi:
# mvn.mchv.eu (tdlight-java manbai) network muammosiga bog'liq emas, chunki bu yerda PyPI ishlatiladi.
FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir --upgrade pip

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY reader.py .

RUN mkdir -p /sessions
VOLUME /sessions

ENV PYTHONUNBUFFERED=1

CMD ["python", "reader.py", "run"]
