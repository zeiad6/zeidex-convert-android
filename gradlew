#!/usr/bin/env sh
# Gradle wrapper script for Zeidex Convert Android

DIRNAME=""
case "$0" in
  /*) DIRNAME=`dirname "$0"` ;;
  *) DIRNAME=`dirname "$PWD/$0"` ;;
esac

GRADLE_BIN="/root/gradle/gradle-8.10.2/bin/gradle"
if [ -x "$GRADLE_BIN" ]; then
  exec "$GRADLE_BIN" "$@"
elif which gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle not found. Please install Gradle 8.10+." >&2
  exit 1
fi
