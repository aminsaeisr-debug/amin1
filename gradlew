#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
if [[ ! -f "$PROPERTIES" ]]; then
  echo "Missing $PROPERTIES" >&2
  exit 1
fi

DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES" | sed 's/\\//g' | head -n 1)
if [[ -z "$DISTRIBUTION_URL" ]]; then
  echo "distributionUrl is missing in $PROPERTIES" >&2
  exit 1
fi

ZIP_NAME="$(basename "${DISTRIBUTION_URL%%\?*}")"
VERSION="${ZIP_NAME#gradle-}"
VERSION="${VERSION%-bin.zip}"
CACHE_DIR="$HOME/.gradle/wrapper/dists/gradle-${VERSION}-bin"
GRADLE_BIN="$(find "$CACHE_DIR" -type f -path '*/bin/gradle' 2>/dev/null | head -n 1 || true)"

if [[ -z "$GRADLE_BIN" ]]; then
  mkdir -p "$CACHE_DIR"
  ZIP_PATH="$HOME/.gradle/wrapper/$ZIP_NAME"
  if [[ ! -f "$ZIP_PATH" ]]; then
    echo "Downloading Gradle $VERSION..."
    curl --fail --location --retry 3 --silent --show-error -o "$ZIP_PATH" "$DISTRIBUTION_URL"
  fi
  unzip -q -o "$ZIP_PATH" -d "$CACHE_DIR"
  GRADLE_BIN="$(find "$CACHE_DIR" -type f -path '*/bin/gradle' 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$GRADLE_BIN" || ! -x "$GRADLE_BIN" ]]; then
  echo "Gradle $VERSION was not installed correctly." >&2
  exit 1
fi

exec "$GRADLE_BIN" "$@"
