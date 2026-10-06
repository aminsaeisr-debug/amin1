#!/usr/bin/env bash
set -euo pipefail

# LOCAL ONLY: never commit the generated keystore or its passwords.
KEYSTORE_FILE="${1:-seed-mas-release.jks}"
ALIAS="${2:-seedmas}"

echo "Creating release keystore: ${KEYSTORE_FILE}"
echo "Choose strong, unique passwords when keytool prompts you."
echo "Keep the .jks file offline and outside the repository."

keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

if command -v base64 >/dev/null 2>&1; then
  base64 -w 0 "$KEYSTORE_FILE" > "${KEYSTORE_FILE}.b64"
else
  base64 "$KEYSTORE_FILE" | tr -d '\n' > "${KEYSTORE_FILE}.b64"
fi

echo
echo "Created:"
echo "  $KEYSTORE_FILE"
echo "  ${KEYSTORE_FILE}.b64"
echo
echo "Store the Base64 text and passwords only in GitHub Actions Secrets:"
echo "  SEED_MAS_KEYSTORE_B64"
echo "  SEED_MAS_STORE_PASSWORD"
echo "  SEED_MAS_KEY_ALIAS"
echo "  SEED_MAS_KEY_PASSWORD"
