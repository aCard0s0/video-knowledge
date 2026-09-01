# VidIngest operator console — Angular build served by nginx.
#
# The same bundle is also baked into the server jar (see the `web` stage of the vidingest
# Dockerfile), which is what makes the jar deployable on its own. This image is the alternative for
# a compose deployment: nginx serves the static files and proxies the API, so the console can be
# rebuilt and restarted without touching the server.
#
# Build context is this directory, not the repo root — nothing here needs the Maven reactor, and a
# narrow context means the image cache only invalidates when the console actually changes.

ARG NODE_IMAGE=node:24-alpine
# The unprivileged variant so the runtime stage is a non-root user without extra Dockerfile work,
# matching the `USER` convention the Java images follow.
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.27-alpine

# ============================================================================
# Stage 1: build the Angular bundle
# ============================================================================
FROM ${NODE_IMAGE} AS web
WORKDIR /web

# Dependencies first, so an app-only change does not re-run npm ci.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# Must match the server's context path: the router base and every asset URL resolve under it, and
# nginx serves the bundle from /vidingest/ for the same reason.
RUN npm run build -- --base-href=/vidingest/

# ============================================================================
# Stage 2: serve
# ============================================================================
FROM ${NGINX_IMAGE} AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
# Into a /vidingest subdirectory, so `root` + try_files resolves without `alias`.
COPY --from=web /web/dist/webapp/browser/ /usr/share/nginx/html/vidingest/

EXPOSE 8080

# 127.0.0.1, not localhost: inside the container that name resolves to ::1 first, and nginx
# listens on IPv4 only, so the probe fails with "connection refused" against a perfectly healthy
# server — which then blocks anything using `depends_on: service_healthy` forever.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/vidingest/index.html || exit 1
