#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ABI="${ABI:-arm64-v8a}"
SOURCE_DIR="${ESSENTIA_PACKAGE_DIR:-$PROJECT_ROOT/android-package}"
SOURCE_SO="$SOURCE_DIR/jniLibs/$ABI/libessentia.so"
SOURCE_HEADERS="$SOURCE_DIR/include/essentia"
SOURCE_EIGEN="${EIGEN_INCLUDE_DIR:-}"
TARGET_SO_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$ABI"
TARGET_INCLUDE_ROOT="$PROJECT_ROOT/app/src/main/cpp/include"
TARGET_HEADERS="$TARGET_INCLUDE_ROOT/essentia"
TARGET_EIGEN="$TARGET_INCLUDE_ROOT/eigen3"

canonical_path() {
  local dir="${1:-}"
  [[ -n "$dir" ]] || return 1
  [[ -d "$dir" ]] || return 1
  (cd "$dir" && pwd -P)
}

if [[ -z "$SOURCE_EIGEN" ]]; then
  if [[ -d "$SOURCE_DIR/include/eigen3" ]]; then
    SOURCE_EIGEN="$SOURCE_DIR/include/eigen3"
  elif [[ -d "$PROJECT_ROOT/third_party/eigen3" ]]; then
    SOURCE_EIGEN="$PROJECT_ROOT/third_party/eigen3"
  elif [[ -d "$TARGET_EIGEN" ]]; then
    SOURCE_EIGEN="$TARGET_EIGEN"
  elif [[ -d "/usr/include/eigen3" ]]; then
    SOURCE_EIGEN="/usr/include/eigen3"
  elif [[ -d "/usr/local/include/eigen3" ]]; then
    SOURCE_EIGEN="/usr/local/include/eigen3"
  elif [[ -d "/opt/homebrew/include/eigen3" ]]; then
    SOURCE_EIGEN="/opt/homebrew/include/eigen3"
  fi
fi

[[ -f "$SOURCE_SO" ]] || { echo "Missing source library: $SOURCE_SO" >&2; exit 1; }
[[ -d "$SOURCE_HEADERS" ]] || { echo "Missing source headers: $SOURCE_HEADERS" >&2; exit 1; }
[[ -d "$SOURCE_EIGEN" ]] || {
  echo "Missing eigen headers: ${SOURCE_EIGEN:-<empty>}" >&2
  echo "Set EIGEN_INCLUDE_DIR (e.g., /path/to/eigen3)." >&2
  exit 1
}

mkdir -p "$TARGET_SO_DIR" "$TARGET_INCLUDE_ROOT"
rm -rf "$TARGET_HEADERS"

cp -f "$SOURCE_SO" "$TARGET_SO_DIR/"
cp -RL "$SOURCE_HEADERS" "$TARGET_INCLUDE_ROOT/"

SOURCE_EIGEN_CANON="$(canonical_path "$SOURCE_EIGEN" || true)"
TARGET_EIGEN_CANON="$(canonical_path "$TARGET_EIGEN" || true)"
if [[ -n "$SOURCE_EIGEN_CANON" && -n "$TARGET_EIGEN_CANON" && "$SOURCE_EIGEN_CANON" == "$TARGET_EIGEN_CANON" ]]; then
  echo "Reusing existing eigen headers at: $TARGET_EIGEN"
else
  rm -rf "$TARGET_EIGEN"
  cp -RL "$SOURCE_EIGEN" "$TARGET_INCLUDE_ROOT/"
fi

echo "Synced Essentia artifacts"
echo "  abi:     $ABI"
echo "  lib:     $TARGET_SO_DIR/libessentia.so"
echo "  headers: $TARGET_HEADERS"
echo "  eigen:   $SOURCE_EIGEN -> $TARGET_EIGEN"
