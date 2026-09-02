# 1-bosqich: build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# 2-bosqich: run
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Tashkent
# OCR: guruhga izohsiz tashlangan karta skrinshotlaridan qoldiqni o'qish uchun
# OCR (tesseract) + kunlik hisobot jadvalini PNG chizish uchun shriftlar (fontconfig, DejaVu — kirill ham bor)
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/kassa-nazorati-*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
