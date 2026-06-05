#!/usr/bin/env bash
# Fetch the sherpa-onnx prebuilt Android native libs (libsherpa-onnx-jni.so + libonnxruntime.so)
# and drop them into app/src/main/jniLibs/<abi>/. These are NOT committed to git (see .gitignore);
# run this once after cloning (and after bumping SHERPA_VERSION) so the on-device sherpa engine
# (SenseVoice / Paraformer / Qwen3) has its runtime libraries.
#
# The Kotlin API for sherpa-onnx is vendored under app/src/main/java/com/k2fsa/sherpa/onnx/ and is
# pinned to the same SHERPA_VERSION — bump both together.
#
# Usage: scripts/fetch-sherpa-libs.sh
set -euo pipefail

SHERPA_VERSION="v1.13.2"
ARCHIVE="sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/${ARCHIVE}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="${REPO_ROOT}/app/src/main/jniLibs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "→ Downloading ${ARCHIVE} (~50 MB) ..."
curl -fL "$URL" -o "${TMP}/${ARCHIVE}"

echo "→ Extracting ..."
tar -xjf "${TMP}/${ARCHIVE}" -C "$TMP"

# The tarball lays out ./jniLibs/<abi>/*.so. Only the JNI + onnxruntime libs are needed for the
# Kotlin OfflineRecognizer path (the c-api/cxx-api libs are for native C/C++ callers).
SRC="${TMP}/jniLibs"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  if [ -d "${SRC}/${abi}" ]; then
    mkdir -p "${JNILIBS}/${abi}"
    cp "${SRC}/${abi}/libsherpa-onnx-jni.so" "${JNILIBS}/${abi}/"
    cp "${SRC}/${abi}/libonnxruntime.so" "${JNILIBS}/${abi}/"
    echo "  ✓ ${abi}"
  else
    echo "  ⚠ ${abi} not found in archive, skipped"
  fi
done

echo "✓ sherpa-onnx ${SHERPA_VERSION} native libs installed under app/src/main/jniLibs/"
