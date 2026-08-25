# VidIngest MCP Dockerfile
# Multi-stage build: compile with full JDK, run with minimal JRE
# Build context: repository root (enables Maven reactor builds)

ARG JDK_IMAGE=eclipse-temurin:25-jdk-alpine
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine
ARG MAVEN_VERSION=3.9.11
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_USER=spring
ARG PORT=8055
ARG HEALTH_PATH=/vidingest/actuator/health
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

COPY docker/scripts/install-maven.sh /usr/local/bin/install-maven
RUN chmod +x /usr/local/bin/install-maven && /usr/local/bin/install-maven "${MAVEN_VERSION}"

# Download the OpenTelemetry Java agent (pinned).
RUN apk add --no-cache curl \
    && mkdir -p /workspace/.otel \
    && curl -fsSL -o /workspace/.otel/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar"

COPY . .

# Provide git metadata for Spring Boot Actuator /info without copying .git into the Docker context.
RUN printf "git.branch=%s\ngit.commit.id=%s\ngit.commit.time=%s\n" "$GIT_BRANCH" "$GIT_COMMIT" "$GIT_COMMIT_TIME" \
    > applications/vidingest/vidingest-mcp/src/main/resources/git.properties

COPY docker/settings.xml /root/.m2/settings.xml

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -ntp -pl applications/vidingest/vidingest-mcp -am clean package -DskipTests -Dmaven.gitcommitid.skip=true

# ============================================================================
# Stage 2: Runtime - Minimal JRE image with application JAR
# ============================================================================
FROM ${JRE_IMAGE}

ARG APP_UID
ARG APP_GID
ARG APP_USER
ARG PORT
ARG HEALTH_PATH

RUN apk add --no-cache curl

COPY docker/scripts/create-app-user.sh /usr/local/bin/create-app-user
RUN chmod +x /usr/local/bin/create-app-user && /usr/local/bin/create-app-user "${APP_USER}" "${APP_UID}" "${APP_GID}"

WORKDIR /app

COPY --from=builder /workspace/applications/vidingest/vidingest-mcp/target/*.jar app.jar
COPY --from=builder /workspace/.otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

RUN mkdir -p /app/logs /app/tmp /otel && chown -R "${APP_USER}:${APP_USER}" /app

USER ${APP_USER}

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"

EXPOSE ${PORT}

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}${HEALTH_PATH}" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

