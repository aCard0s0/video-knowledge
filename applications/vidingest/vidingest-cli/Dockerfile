# VidIngest CLI Dockerfile
# Spring Shell interactive CLI for VidIngest REST API
# Multi-stage build: compile with full JDK, run with minimal JRE
# Build context: repository root (enables Maven reactor builds)

# Build-time arguments with defaults (override via --build-arg or compose)
ARG JDK_IMAGE=eclipse-temurin:25-jdk-alpine  # Full JDK for Maven builds
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine  # Minimal JRE for runtime
ARG MAVEN_VERSION=3.9.11                     # Pinned Maven version
ARG APP_UID=1000                             # Non-root user ID
ARG APP_GID=1000                             # Non-root group ID
ARG APP_USER=app                             # CLI user (convention: 'app' for CLIs)
ARG OTEL_JAVAAGENT_VERSION=2.26.1

# ============================================================================
# Stage 1: Builder - Compile and package the CLI application
# ============================================================================
FROM ${JDK_IMAGE} AS builder
ARG MAVEN_VERSION
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

# Copy entire repo for Maven reactor build
COPY . .

# Maven settings.xml for Spring repository access
COPY docker/settings.xml /root/.m2/settings.xml

# Build VidIngest CLI and all required upstream modules
# Includes vidingest-client library dependency
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -ntp -pl applications/vidingest/vidingest-cli -am clean package -DskipTests

# ============================================================================
# Stage 2: Runtime - Minimal JRE with Spring Shell CLI
# ============================================================================
FROM ${JRE_IMAGE}

# Re-declare ARGs needed in runtime stage
ARG APP_UID
ARG APP_GID
ARG APP_USER

WORKDIR /app

# Create non-root user for CLI execution
COPY docker/scripts/create-app-user.sh /usr/local/bin/create-app-user
RUN chmod +x /usr/local/bin/create-app-user \
    && /usr/local/bin/create-app-user "${APP_USER}" "${APP_UID}" "${APP_GID}" \
    && mkdir -p /otel \
    && chown -R "${APP_USER}:${APP_USER}" /otel

# Copy compiled CLI JAR from builder
COPY --from=builder /workspace/applications/vidingest/vidingest-cli/target/vidingest-cli-*.jar app.jar
COPY --from=builder /workspace/.otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

# Install launcher script (wraps java -jar with Spring Shell modes)
COPY applications/vidingest/vidingest-cli/docker/vidingest-cli-launcher.sh /usr/local/bin/vidingest-cli

# Make launcher executable, create 'vi' alias, set ownership
RUN chmod +x /usr/local/bin/vidingest-cli \
    && ln -s /usr/local/bin/vidingest-cli /usr/local/bin/vi \
    && chown -R "${APP_USER}:${APP_USER}" /app

USER ${APP_USER}

ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC"

ENTRYPOINT ["vidingest-cli"]

