# -------- Build stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# copy everything (multi-module project)
COPY . .

# embed commit ID into app so GET /commit.txt returns deployed commit (CI writes commit.txt before build)
RUN mkdir -p app/src/main/resources/static && \
    (test -f commit.txt && cp commit.txt app/src/main/resources/static/commit.txt) || \
    echo "local-build" > app/src/main/resources/static/commit.txt

# build only the app module and its dependencies
RUN mvn -pl app -am clean package -DskipTests
# if your main module isn't "app", replace "app" above and below with that module name.


# -------- Runtime stage --------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Convert build arguments to environment variables
ENV DB_URI=${DB_URI}
ENV CLIENT_URL=${CLIENT_URL}
ENV OPENAI_API_KEY=${OPENAI_API_KEY}
ENV AWS_ACCESS_KEY=${AWS_ACCESS_KEY}
ENV AWS_SECRET_ACCESS=${AWS_SECRET_ACCESS}
ENV AWS_REGION=${AWS_REGION}
ENV MESSAGING_EMAIL_ENABLED=${MESSAGING_EMAIL_ENABLED}
ENV MESSAGING_DISPATCH_INTERVAL_MS=${MESSAGING_DISPATCH_INTERVAL_MS}
ENV RESEND_API_KEY=${RESEND_API_KEY}
ENV RESEND_FROM_EMAIL=${RESEND_FROM_EMAIL}
ENV RESEND_FROM_NAME=${RESEND_FROM_NAME}

# copy the built jar from the app module (commit.txt is inside as static resource)
COPY --from=build /build/app/target/*.jar app.jar

EXPOSE 8080

# G1: small start, cap below cgroup so RSS (heap + metaspace + threads + native) fits.
# Periodic concurrent GC uncommits idle heap back to the OS (otherwise committed/RSS only grow).
# ExitOnOutOfMemoryError: on heap exhaustion exit at once (the platform restarts the container in
# seconds) instead of staying up half-broken — threads die one by one, requests hang for minutes.
# TrimNativeHeapInterval + MALLOC_ARENA_MAX: give freed native (non-heap) memory back to the OS.
# glibc keeps memory freed by PDF/zip/font code in per-thread malloc arenas, so RSS never fell
# after load (production load test: ~400 MiB stuck). With both: after a burst of 96 PDF/Excel
# downloads native memory kept +15 MiB instead of +46 MiB; after a one-year PDF it was back to
# its starting level within ~2 min. TrimNativeHeapInterval: the JVM calls malloc_trim every 30 s
# (product flag since JDK 21.0.1). MALLOC_ARENA_MAX: at most 2 arenas instead of 8 per CPU.
# Override JAVA_TOOL_OPTIONS on the host if needed. Idle RSS is hundreds of MiB; use ≥1 GB RAM in prod.
ENV MALLOC_ARENA_MAX=2
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=5.0 -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -XX:G1PeriodicGCInterval=15000 -XX:+G1PeriodicGCInvokesConcurrent -XX:+ExitOnOutOfMemoryError -XX:TrimNativeHeapInterval=30000"

ENTRYPOINT ["java", "-jar", "app.jar"]
