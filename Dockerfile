# syntax=docker/dockerfile:1
# 1-bosqich: build. pom.xml/`.m2-tdlib-cache` o'zgarmasa — Docker qatlam keshi tufayli qayta yuklanmaydi.
# 📨 «tdlib» profili: to'liq Java TDLib mijozi (src/tdlib/java). MUHIM: ba'zi serverlarda BuildKit'ning
# RUN tarmog'i mvn.mchv.eu ga ulana olmaydi, garchi oddiy `docker run`/`curl` shu serverdan ulansa ham
# (BuildKit alohida tarmoq yo'lidan foydalanadi — ma'lum muammo). Shuning uchun it.tdlight:* artifaktlari
# BUILD PAYTIDA YUKLANMAYDI — `.m2-tdlib-cache/` papkasidan oldindan KO'CHIRILADI (bir martalik `docker run`
# bilan to'ldiriladi, docs/BOT-XABARLARI.md). Bo'sh bo'lsa (.gitkeep) — hech narsa buzilmaydi, faqat
# `-Ptdlib` bilan build mchv.eu'ga murojaat qilib xato beradi (kutilgan, kesh to'ldirilmagan bo'lsa).
FROM maven:3.9-eclipse-temurin-17 AS build
ARG TDLIB=-Ptdlib
WORKDIR /app
# TDLib keshi (o'zgarsa qatlam qayta ishlaydi, o'zgarmasa Docker uni qayta ishlatadi — BuildKit cache
# mount ISHLATILMAYDI, chunki u shu COPY qilingan /root/.m2 ni RUN vaqtida yashirib qo'yardi).
COPY .m2-tdlib-cache/ /root/.m2/
COPY pom.xml .
RUN mvn -q -B $TDLIB dependency:go-offline || true
COPY src ./src
# Vaqtinchalik tarmoq nosozligiga chidamli: 3 urinish (faqat Central uchun — TDLib yuqorida oldindan bor).
RUN for i in 1 2 3; do \
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
