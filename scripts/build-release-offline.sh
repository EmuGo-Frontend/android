#!/bin/sh
# Builds the signed release APK. Signing credentials are read from the environment:
#   SIGNING_KEYSTORE        path to the .jks keystore (kept outside the repository)
#   SIGNING_KEY_ALIAS       key alias inside the keystore
#   SIGNING_STORE_PASSWORD  keystore password
#   SIGNING_KEY_PASSWORD    key password (defaults to the store password)
set -eu
: "${SIGNING_KEYSTORE:?Set SIGNING_KEYSTORE to the release keystore}"
: "${SIGNING_STORE_PASSWORD:?Set SIGNING_STORE_PASSWORD}"
export SIGNING_KEY_ALIAS="${SIGNING_KEY_ALIAS:-upload}"
export SIGNING_KEY_PASSWORD="${SIGNING_KEY_PASSWORD:-$SIGNING_STORE_PASSWORD}"
export SIGNING_KEYSTORE SIGNING_STORE_PASSWORD
export VERSION_NAME="${VERSION_NAME:-1.0.7}"
export VERSION_CODE="${VERSION_CODE:-107}"
workspace=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export OUTPUT_APK="${OUTPUT_APK:-$workspace/EmuGo-$VERSION_NAME.apk}"
exec sh "$workspace/scripts/build-debug-offline.sh"
