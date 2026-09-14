# syntax=docker/dockerfile:1
# 1-bosqich: build. Maven keshi (~/.m2) BuildKit cache mount'da saqlanadi —
# har rebuild'da bog'liqliklar/plaginlar qayta yuklanmaydi, faqat o'zgargan kod kompilyatsiya bo'ladi.
# 📨 «tdlib» profili: to'liq Java TDLib mijozi (src/tdlib/java). mvn.mchv.eu ochiq bo'lishi kerak;
# ochilmasa build shu bosqichda to'xtaydi — TDLib'siz build uchun `--build-arg TDLIB=` bering.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG TDLIB=-Ptdlib
# IPv4'ga majburlash (ba'zi serverlarda konteyner tarmog'ida IPv6 marshruti buzuq — ulanish
# "Connect timed out" bilan osilib qoladi) + mchv.eu resolve/connect uzoqroq kutilsin (sekin tarmoq).
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true -Dmaven.wagon.http.connectionTimeout=60000 -Dmaven.wagon.http.readTimeout=60000"
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B $TDLIB dependency:go-offline || true
COPY src ./src
# Vaqtinchalik tarmoq nosozligiga chidamli: 3 marta urinadi (mchv.eu bir zumda javob bermasa ham).
RUN --mount=type=cache,target=/root/.m2 \
    for i in 1 2 3; do \
      mvn -q -B $TDLIB -DskipTests package && break; \
      echo "mvn urinish $i muvaffaqiyatsiz, qayta..." >&2; sleep 5; \
      if [ "$i" = 3 ]; then exit 1; fi; \
    done

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
