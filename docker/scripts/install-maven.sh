#!/bin/sh
# Install Apache Maven in Alpine Linux Docker images
# Downloads, validates, and installs specified Maven version
# Usage: install-maven.sh [VERSION]
# Default version: 3.9.11

set -eu  # Exit on error, fail on undefined variables

# Maven version to install (default: 3.9.11, override via first argument)
MAVEN_VERSION="${1:-3.9.11}"
MAVEN_TGZ="apache-maven-${MAVEN_VERSION}-bin.tar.gz"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/${MAVEN_TGZ}"

# Install wget and tar temporarily (needed for download and extraction)
apk add --no-cache wget tar

# Download Maven binary distribution from Apache Maven Central Repository
wget "${MAVEN_URL}"

# Extract Maven to /opt directory (standard location for third-party software)
tar xzf "${MAVEN_TGZ}" -C /opt

# Create symlink in /usr/bin for easy PATH access
# Allows 'mvn' command without full path
ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/bin/mvn

# Clean up downloaded tarball (reduces Docker layer size)
rm "${MAVEN_TGZ}"

# Remove wget package (no longer needed, reduces final image size)
# tar is kept because it's commonly useful in containers
apk del wget
