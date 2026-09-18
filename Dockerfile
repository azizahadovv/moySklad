# syntax=docker/dockerfile:1
# 1-bosqich: build. Maven keshi (/root/.m2) BuildKit cache mount'da (id=kassa-m2) — build'lar orasida
# saqlanadi, har rebuild'da hammasi qaytadan yuklanmaydi.
# 📨 «tdlib» profili (src/tdlib/java, to'liq Java TDLib mijozi) O'CHIQ QOLDI (2026-09-15): uni ta'minlaydigan
# it.tdlight:tdlight-java FAQAT mvn.mchv.eu'da bor, va bu server ko'p tarmoqdan (jumladan BuildKit'ning
# alohida RUN tarmog'idan) umuman ulanmaydi — real production o'chib qolishiga sabab bo'lgan. O'rniga
# «🔗 Ulangan akkauntlar» endi alohida `tg-reader` xizmati (Python/Telethon, PyPI'dan — tg-reader/Dockerfile)
# orqali ishlaydi, shu jarayonga bog'liq emas. `-Ptdlib` hali profil sifatida mavjud (kerak bo'lsa qo'lda
# yoqiladi: `--build-arg TDLIB=-Ptdlib`), lekin standart build unga UMUMAN MUROJAAT QILMAYDI.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG TDLIB=
# Maven 3.9 standart transporti resolver (wagon emas) — maven.wagon.* kalitlari unga ta'sir qilmaydi, standart
# so'rov timeout'i 30 daqiqa edi: osilgan bitta yuklash butun build'ni «qotib» qo'yardi (2026-09-18).
ENV MAVEN_OPTS="-Daether.connector.connectTimeout=15000 -Daether.connector.requestTimeout=90000"
WORKDIR /app
COPY .m2-tdlib-cache/ /opt/tdlib-seed/
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    cp -rn /opt/tdlib-seed/. /root/.m2/ 2>/dev/null; \
    mvn -q -B $TDLIB dependency:go-offline || true
COPY src ./src
# Vaqtinchalik tarmoq nosozligiga chidamli: 3 urinish (faqat Central uchun — TDLib yuqorida oldindan bor).
RUN --mount=type=cache,target=/root/.m2 \
    cp -rn /opt/tdlib-seed/. /root/.m2/ 2>/dev/null; \
    for i in 1 2 3; do \
      mvn -B -ntp $TDLIB -DskipTests package && break; \
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
