# VidIngest Server Dockerfile
# Spring MVC server + MCP SSE transport + JPA/Liquibase + yt-dlp integration
# Multi-stage build: compile with full JDK, run with minimal JRE + Python
# Build context: repository root (enables Maven reactor builds)

# Build-time arguments with defaults (override via --build-arg or compose)
ARG JDK_IMAGE=eclipse-temurin:26-jdk-alpine
ARG JRE_IMAGE=eclipse-temurin:26-jre-alpine
ARG MAVEN_VERSION=3.9.11
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_USER=spring
ARG GIT_COMMIT=unknown
ARG GIT_BRANCH=unknown
ARG GIT_COMMIT_TIME=unknown
ARG OTEL_JAVAAGENT_VERSION=2.26.1

# ============================================================================
# Stage 1: Builder - Compile and package the application
# ============================================================================
FROM ${JDK_IMAGE} AS builder
ARG MAVEN_VERSION
ARG GIT_COMMIT
ARG GIT_BRANCH
ARG GIT_COMMIT_TIME
ARG OTEL_JAVAAGENT_VERSION

WORKDIR /workspace

# Install Maven using shared script
COPY docker/scripts/install-maven.sh /usr/local/bin/install-maven
RUN chmod +x /usr/local/bin/install-maven && /usr/local/bin/install-maven "${MAVEN_VERSION}"

# Download the OpenTelemetry Java agent (pinned).
RUN apk add --no-cache curl \
    && mkdir -p /workspace/.otel \
    && curl -fsSL -o /workspace/.otel/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar"

# Copy entire repository for Maven multi-module reactor build
COPY . .

# Provide git metadata for Spring Boot Actuator /info without copying .git into the Docker context.
RUN printf "git.branch=%s\ngit.commit.id=%s\ngit.commit.time=%s\n" "$GIT_BRANCH" "$GIT_COMMIT" "$GIT_COMMIT_TIME" \
    > applications/vidingest/vidingest-server/src/main/resources/git.properties

# Maven settings.xml enables Spring snapshot/milestone repositories
COPY docker/settings.xml /root/.m2/settings.xml

# Build VidIngest server and resolve all transitive dependencies
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -ntp -pl applications/vidingest/vidingest-server -am clean package -DskipTests -Dmaven.gitcommitid.skip=true

# ============================================================================
# Stage 2: Runtime - Minimal JRE + Python/yt-dlp for video downloads
# ============================================================================
FROM ${JRE_IMAGE}

ARG APP_UID
ARG APP_GID
ARG APP_USER

# Install runtime dependencies for video ingestion
RUN apk add --no-cache \
    python3 \
    py3-pip \
    ffmpeg \
    deno \
    && pip3 install --break-system-packages --no-cache-dir yt-dlp \
    && yt-dlp --version

# Create non-root user for security
COPY docker/scripts/create-app-user.sh /usr/local/bin/create-app-user
RUN chmod +x /usr/local/bin/create-app-user && /usr/local/bin/create-app-user "${APP_USER}" "${APP_UID}" "${APP_GID}"

# Create writable directories for video storage and processing
RUN mkdir -p /data/videos /tmp/vidingest /app/logs /app/tmp /otel \
    && chown -R "${APP_USER}:${APP_USER}" /data /tmp/vidingest /app

WORKDIR /app

# Copy compiled JAR from builder stage
COPY --from=builder /workspace/applications/vidingest/vidingest-server/target/vidingest-server-*.jar app.jar
COPY --from=builder /workspace/.otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

# Set ownership for application JAR
RUN chown "${APP_USER}:${APP_USER}" /app/app.jar

# Switch to non-root user
USER ${APP_USER}

ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    SPRING_PROFILES_ACTIVE="docker"

# Internal container port (host mapping is defined in compose)
EXPOSE 8051

# Health check: probe readiness endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD sh -c 'wget --no-verbose --tries=1 --spider "http://localhost:${SERVER_PORT:-8051}/vidingest/api/v1/health/ready" 2>/dev/null || exit 1'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

