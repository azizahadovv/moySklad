# syntax=docker/dockerfile:1
# 1-bosqich: build. Maven keshi (~/.m2) BuildKit cache mount'da saqlanadi —
# har rebuild'da bog'liqliklar/plaginlar qayta yuklanmaydi, faqat o'zgargan kod kompilyatsiya bo'ladi.
# 📨 «tdlib» profili: to'liq Java TDLib mijozi (src/tdlib/java). mvn.mchv.eu ochiq bo'lishi kerak;
# ochilmasa build shu bosqichda to'xtaydi — TDLib'siz build uchun `--build-arg TDLIB=` bering.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG TDLIB=-Ptdlib
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B $TDLIB dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -o $TDLIB -DskipTests package \
    || mvn -q -B $TDLIB -DskipTests package

# 2-bosqich: run
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Tashkent
# OCR (tesseract) + hisobot PNG shriftlari (fontconfig, DejaVu — kirill) + TDLib native talablari (OpenSSL3, zlib, libc++)
RUN apt-get update && apt-get install -y --no-install-recommends \
      tesseract-ocr fontconfig fonts-dejavu-core \
      libssl3 zlib1g libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/kassa-nazorati-*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
