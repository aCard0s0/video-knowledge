#!/bin/sh
set -eu

APP_JAR="${VIDINGEST_CLI_JAR:-/app/app.jar}"
SPRING_SHELL_CONFIG_DIR="${SPRING_SHELL_CONFIG_DIR:-${HOME:-/tmp}/.spring-shell}"

mkdir -p "$SPRING_SHELL_CONFIG_DIR"

history_opts() {
  printf '%s' \
    "-Dspring.shell.config.location=${SPRING_SHELL_CONFIG_DIR} " \
    '-Dspring.shell.history.name=vidingest-cli.log '
}

# Each `docker exec ... vi <cmd>` is a separate process: it starts a fresh JVM for that
# one-shot run. PID 1 (no args) is the long-lived interactive Spring Shell.
# For non-interactive runs, default to quiet logging unless VIDINGEST_CLI_VERBOSE is 1/true.
quiet_noninteractive_opts() {
  case "${VIDINGEST_CLI_VERBOSE:-}" in
    1|true|yes) ;;
    *)
      printf '%s' \
        '-Dspring.main.log-startup-info=false ' \
        '-Dlogging.level.org.springframework.boot=WARN ' \
        '-Dlogging.level.org.springframework.context=WARN ' \
        '-Dlogging.level.com.tradinglabs.vidingest.VidingestCliApp=WARN '
      ;;
  esac
}

if [ "$#" -eq 0 ]; then
  exec java ${JAVA_OPTS:-} $(history_opts) -jar "$APP_JAR"
fi

exec java ${JAVA_OPTS:-} $(history_opts) $(quiet_noninteractive_opts) \
  -Dspring.shell.interactive.enabled=false \
  -Dspring.shell.noninteractive.enabled=true \
  -jar "$APP_JAR" \
  "$@"

