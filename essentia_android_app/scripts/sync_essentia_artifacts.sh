#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SOURCE_DIR="${ESSENTIA_PACKAGE_DIR:-/Users/iriver/hwan/essentia_compile/essentia/android-package}"
SOURCE_SO="$SOURCE_DIR/jniLibs/arm64-v8a/libessentia.so"
SOURCE_HEADERS="$SOURCE_DIR/include/essentia"
SOURCE_EIGEN="${EIGEN_INCLUDE_DIR:-/opt/homebrew/include/eigen3}"

TARGET_SO_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
TARGET_INCLUDE_ROOT="$PROJECT_ROOT/app/src/main/cpp/include"
TARGET_HEADERS="$TARGET_INCLUDE_ROOT/essentia"
TARGET_EIGEN="$TARGET_INCLUDE_ROOT/eigen3"

[[ -f "$SOURCE_SO" ]] || { echo "Missing source library: $SOURCE_SO" >&2; exit 1; }
[[ -d "$SOURCE_HEADERS" ]] || { echo "Missing source headers: $SOURCE_HEADERS" >&2; exit 1; }
[[ -d "$SOURCE_EIGEN" ]] || { echo "Missing eigen headers: $SOURCE_EIGEN" >&2; exit 1; }

mkdir -p "$TARGET_SO_DIR" "$TARGET_INCLUDE_ROOT"
rm -rf "$TARGET_HEADERS" "$TARGET_EIGEN"

cp -f "$SOURCE_SO" "$TARGET_SO_DIR/"
cp -RL "$SOURCE_HEADERS" "$TARGET_INCLUDE_ROOT/"
cp -RL "$SOURCE_EIGEN" "$TARGET_INCLUDE_ROOT/"

echo "Synced Essentia artifacts"
echo "  lib:     $TARGET_SO_DIR/libessentia.so"
echo "  headers: $TARGET_HEADERS"
echo "  eigen:   $TARGET_EIGEN"
