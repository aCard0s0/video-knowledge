#!/bin/sh
# Create a non-root user in Alpine Linux Docker images
# Creates both a group and user with specified UID/GID for security best practices
# Usage: create-app-user.sh [USERNAME] [UID] [GID]
# Defaults: username=spring, uid=1000, gid=1000

set -eu  # Exit on error, fail on undefined variables

# User/group configuration (override via arguments)
APP_USER="${1:-spring}"  # Username for the application service account
APP_UID="${2:-1000}"     # User ID (1000 matches typical developer workstation user)
APP_GID="${3:-1000}"     # Group ID (1000 matches typical developer workstation group)

# Create group first (Alpine requires group to exist before adding user to it)
# -g: specifies the GID (group ID)
addgroup -g "${APP_GID}" "${APP_USER}"

# Create user and add to the group
# -D: don't assign a password (service accounts don't need login passwords)
# -u: specifies the UID (user ID)
# -G: adds user to the specified group
# This creates a system user that can own files and run processes but can't login
adduser -D -u "${APP_UID}" -G "${APP_USER}" "${APP_USER}"
