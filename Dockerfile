# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /workspace

# Copy the build descriptors first so Docker can reuse dependency layers.
COPY pom.xml package.json package-lock.json angular.json tsconfig*.json .nvmrc ./
COPY public ./public
COPY src ./src

RUN mvn -B -DskipTests package

FROM eclipse-temurin:26-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 karaoke

WORKDIR /app

COPY --from=build /workspace/target/karaoke-studio-*.jar /app/app.jar

RUN mkdir -p /tmp/karaoke-studio-youtube \
    && chown -R karaoke:karaoke /app /tmp/karaoke-studio-youtube

USER karaoke

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    FFMPEG_PATH=ffmpeg \
    YOUTUBE_CACHE_DIR=/tmp/karaoke-studio-youtube \
    KARAOKE_LOG_FILE=/tmp/karaoke-studio.log

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
