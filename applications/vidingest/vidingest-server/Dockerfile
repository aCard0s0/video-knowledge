# VidIngest Server Dockerfile
# Spring MVC server + MCP SSE transport + JPA/Liquibase + yt-dlp integration
# Multi-stage build: compile with full JDK, run with minimal JRE + Python
# Build context: repository root (enables Maven reactor builds)
#
# This image serves the API only. The operator console used to be built here in a node stage and
# baked into classpath:/static; it now lives in its own image (applications/webapp/Dockerfile,
# nginx) so it can be rebuilt without the server. One console, one place — the two copies could
# only ever diverge by rebuilding one and not the other.

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

# ============================================================================
# Stage 1: Builder - Compile and package the application
# ============================================================================
FROM ${JDK_IMAGE} AS builder
ARG MAVEN_VERSION
ARG GIT_COMMIT
ARG GIT_BRANCH
ARG GIT_COMMIT_TIME

WORKDIR /workspace

# Install Maven using shared script
COPY docker/scripts/install-maven.sh /usr/local/bin/install-maven
RUN chmod +x /usr/local/bin/install-maven && /usr/local/bin/install-maven "${MAVEN_VERSION}"

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
RUN mkdir -p /data/videos /tmp/vidingest /app/logs /app/tmp \
    && chown -R "${APP_USER}:${APP_USER}" /data /tmp/vidingest /app

WORKDIR /app

# Copy compiled JAR from builder stage
COPY --from=builder /workspace/applications/vidingest/vidingest-server/target/vidingest-server-*.jar app.jar

# Set ownership for application JAR
RUN chown "${APP_USER}:${APP_USER}" /app/app.jar

# Switch to non-root user
USER ${APP_USER}

# No -Xmx: it wins over MaxRAMPercentage, and -Xmx768m against the compose mem_limit of 768M
# left the heap free to grow into every byte the container had. Metaspace, thread stacks and
# the direct buffers the sidecar uploads use all live outside the heap, so the container OOM
# killer fired before -XX:+ExitOnOutOfMemoryError could ever report a Java OOM. The percentage
# is container-aware and re-scales on its own if mem_limit changes. Matches vidingest-mcp.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    SPRING_PROFILES_ACTIVE="docker"

# Internal container port (host mapping is defined in compose)
EXPOSE 8051

# Health check: probe readiness endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD sh -c 'wget --no-verbose --tries=1 --spider "http://localhost:${SERVER_PORT:-8051}/vidingest/api/v1/health/ready" 2>/dev/null || exit 1'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

