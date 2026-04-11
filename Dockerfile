# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Copy submodule build files
COPY core/build.gradle core/build.gradle
COPY raft/build.gradle raft/build.gradle
COPY store/build.gradle store/build.gradle
COPY server/build.gradle server/build.gradle
COPY proto/build.gradle proto/build.gradle
COPY test-harness/build.gradle test-harness/build.gradle

# Copy source
COPY core/src core/src
COPY raft/src raft/src
COPY store/src store/src
COPY server/src server/src
COPY proto/src proto/src

RUN chmod +x gradlew && ./gradlew :server:shadowJar --no-daemon -x test

# --- Runtime stage ---
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/server/build/libs/server.jar /app/server.jar

# Raft persistent state directory
VOLUME ["/data/raft"]

EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
