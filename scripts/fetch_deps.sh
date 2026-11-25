#!/usr/bin/env bash
set -euo pipefail

SHERPA_VERSION="2.0.3"
AAR_NAME="sherpa-onnx-${SHERPA_VERSION}.aar"
AAR_SHA256="4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
DEST_FILE="${DEST_DIR}/${AAR_NAME}"

mkdir -p "${DEST_DIR}"

if [[ -f "${DEST_FILE}" ]]; then
  CURRENT_SHA="$(sha256sum "${DEST_FILE}" | awk '{print $1}')"
  if [[ "${CURRENT_SHA}" == "${AAR_SHA256}" ]]; then
    echo "sherpa-onnx AAR already present and verified: ${DEST_FILE}"
    exit 0
  else
    echo "Checksum mismatch for ${DEST_FILE}, re-downloading..."
    rm -f "${DEST_FILE}"
  fi
fi

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${AAR_NAME}"
echo "Downloading ${URL}..."
curl -fSL "${URL}" -o "${DEST_FILE}"

ACTUAL_SHA="$(sha256sum "${DEST_FILE}" | awk '{print $1}')"
if [[ "${ACTUAL_SHA}" != "${AAR_SHA256}" ]]; then
  echo "ERROR: SHA-256 verification failed! Expected ${AAR_SHA256}, got ${ACTUAL_SHA}" >&2
  rm -f "${DEST_FILE}"
  exit 1
fi

echo "Successfully downloaded and verified ${DEST_FILE}"
