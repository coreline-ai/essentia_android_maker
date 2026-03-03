#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ABI="${ABI:-arm64-v8a}"
API="${API:-}"
PREFIX="${PREFIX:-}"
MODE="${MODE:-release}"
STD="${STD:-c++17}"
IGNORE_ALGOS="${IGNORE_ALGOS:-LPC}"
CLEAN="${CLEAN:-1}"
PACKAGE="${PACKAGE:-0}"
PACKAGE_ONLY="${PACKAGE_ONLY:-0}"
PACKAGE_DIR="${PACKAGE_DIR:-$SCRIPT_DIR/android-package}"

usage() {
  cat <<'EOF'
Usage: ./build_android.sh [options]

Options:
  --abi <abi>        Android ABI: arm64-v8a (default), armeabi-v7a, x86, x86_64
  --api <level>      Android API level (default: 21 for 64-bit, 16 for 32-bit)
  --prefix <path>    Install prefix (default: ./android-install/<abi>)
  --mode <mode>      Waf mode: release (default), debug, default
  --std <std>        C++ standard for waf (default: c++17)
  --ignore-algos <x> Algorithms to ignore (default: LPC)
  --package          Build/install and then package artifacts
  --package-only     Skip build/install and package from existing --prefix
  --package-dir <p>  Packaging root dir (default: ./android-package)
  --no-clean         Skip waf clean
  -h, --help         Show this help

Environment overrides:
  ANDROID_NDK_ROOT / ANDROID_NDK_HOME / NDK_ROOT
  ANDROID_SDK_ROOT / ANDROID_HOME
  ABI, API, PREFIX, MODE, STD, IGNORE_ALGOS, CLEAN
  PACKAGE, PACKAGE_ONLY, PACKAGE_DIR

Examples:
  ./build_android.sh --abi arm64-v8a --package
  ./build_android.sh --abi arm64-v8a --package-only
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi)
      ABI="${2:?missing value for --abi}"
      shift 2
      ;;
    --api)
      API="${2:?missing value for --api}"
      shift 2
      ;;
    --prefix)
      PREFIX="${2:?missing value for --prefix}"
      shift 2
      ;;
    --mode)
      MODE="${2:?missing value for --mode}"
      shift 2
      ;;
    --std)
      STD="${2:?missing value for --std}"
      shift 2
      ;;
    --ignore-algos)
      IGNORE_ALGOS="${2:?missing value for --ignore-algos}"
      shift 2
      ;;
    --package)
      PACKAGE="1"
      shift
      ;;
    --package-only)
      PACKAGE_ONLY="1"
      PACKAGE="1"
      shift
      ;;
    --package-dir)
      PACKAGE_DIR="${2:?missing value for --package-dir}"
      shift 2
      ;;
    --no-clean)
      CLEAN="0"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$PREFIX" ]]; then
  PREFIX="$SCRIPT_DIR/android-install/$ABI"
fi

package_android_artifacts() {
  local src_lib="$PREFIX/lib/libessentia.so"
  local src_include="$PREFIX/include/essentia"
  local src_pc="$PREFIX/lib/pkgconfig/essentia.pc"

  [[ -f "$src_lib" ]] || { echo "Missing library for packaging: $src_lib" >&2; exit 1; }
  [[ -d "$src_include" ]] || { echo "Missing headers for packaging: $src_include" >&2; exit 1; }
  [[ -f "$src_pc" ]] || { echo "Missing pkg-config file for packaging: $src_pc" >&2; exit 1; }

  local dst_jni="$PACKAGE_DIR/jniLibs/$ABI"
  local dst_include_root="$PACKAGE_DIR/include"
  local dst_pkgconfig="$PACKAGE_DIR/pkgconfig"

  rm -rf "$dst_jni" "$dst_include_root/essentia"
  mkdir -p "$dst_jni" "$dst_include_root" "$dst_pkgconfig"

  cp -f "$src_lib" "$dst_jni/"
  cp -RL "$src_include" "$dst_include_root/"
  cp -f "$src_pc" "$dst_pkgconfig/essentia.pc"

  echo "Packaged Android artifacts:"
  echo "  jniLibs:   $dst_jni/libessentia.so"
  echo "  include:   $dst_include_root/essentia"
  echo "  pkgconfig: $dst_pkgconfig/essentia.pc"
}

if [[ "$PACKAGE_ONLY" == "1" ]]; then
  package_android_artifacts
  exit 0
fi

find_latest_ndk() {
  local ndk_parent="$1"
  ls -1 "$ndk_parent" 2>/dev/null | sort -V | tail -n 1
}

resolve_sdk_root() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    echo "$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    echo "$ANDROID_HOME"
    return
  fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    if [[ -d "$HOME/Library/Android/sdk" ]]; then
      echo "$HOME/Library/Android/sdk"
      return
    fi
  fi
  if [[ -d "$HOME/Android/Sdk" ]]; then
    echo "$HOME/Android/Sdk"
    return
  fi
}

resolve_ndk_root() {
  if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    echo "$ANDROID_NDK_ROOT"
    return
  fi
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    echo "$ANDROID_NDK_HOME"
    return
  fi
  if [[ -n "${NDK_ROOT:-}" ]]; then
    echo "$NDK_ROOT"
    return
  fi

  local sdk_root
  sdk_root="$(resolve_sdk_root)"
  if [[ -z "$sdk_root" ]]; then
    return
  fi

  local ndk_parent="$sdk_root/ndk"
  local latest
  latest="$(find_latest_ndk "$ndk_parent")"
  if [[ -n "$latest" ]]; then
    echo "$ndk_parent/$latest"
  fi
}

detect_host_tag() {
  local host_os
  local host_arch
  host_os="$(uname -s)"
  host_arch="$(uname -m)"

  case "$host_os" in
    Darwin) host_os="darwin" ;;
    Linux) host_os="linux" ;;
    MINGW*|MSYS*|CYGWIN*) host_os="windows" ;;
    *) echo "Unsupported host OS: $host_os" >&2; exit 1 ;;
  esac

  case "$host_arch" in
    x86_64|amd64) host_arch="x86_64" ;;
    arm64|aarch64)
      if [[ "$host_os" == "windows" ]]; then
        # Android NDK prebuilt host toolchains are commonly distributed as windows-x86_64.
        host_arch="x86_64"
      else
        host_arch="arm64"
      fi
      ;;
    *) echo "Unsupported host architecture: $host_arch" >&2; exit 1 ;;
  esac

  echo "${host_os}-${host_arch}"
}

map_abi_to_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86) echo "i686-linux-android" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *) echo "Unsupported ABI: $1" >&2; exit 1 ;;
  esac
}

default_api_for_abi() {
  case "$1" in
    arm64-v8a|x86_64) echo "21" ;;
    armeabi-v7a|x86) echo "16" ;;
    *) echo "21" ;;
  esac
}

resolve_tool_path() {
  local base="$1"
  if [[ -x "$base" ]]; then
    echo "$base"
    return
  fi
  if [[ -f "$base.cmd" ]]; then
    echo "$base.cmd"
    return
  fi
  if [[ -f "$base.exe" ]]; then
    echo "$base.exe"
    return
  fi
  return 1
}

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
command -v pkg-config >/dev/null || { echo "pkg-config is required"; exit 1; }
pkg-config --exists eigen3 || { echo "eigen3 pkg-config entry is required"; exit 1; }

NDK_ROOT="$(resolve_ndk_root)"
[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || {
  echo "Could not find Android NDK. Set ANDROID_NDK_ROOT." >&2
  exit 1
}

HOST_TAG="$(detect_host_tag)"
TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -d "$TOOLCHAIN_BIN" && "$HOST_TAG" == "darwin-arm64" ]]; then
  HOST_TAG="darwin-x86_64"
  TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
fi
[[ -d "$TOOLCHAIN_BIN" ]] || {
  echo "NDK toolchain path not found: $TOOLCHAIN_BIN" >&2
  exit 1
}

[[ -n "$API" ]] || API="$(default_api_for_abi "$ABI")"
TARGET_TRIPLE="$(map_abi_to_target "$ABI")"

C_COMPILER_BASE="$TOOLCHAIN_BIN/${TARGET_TRIPLE}${API}-clang"
CXX_COMPILER_BASE="$TOOLCHAIN_BIN/${TARGET_TRIPLE}${API}-clang++"
AR_TOOL_BASE="$TOOLCHAIN_BIN/llvm-ar"
CLANG_BIN_BASE="$TOOLCHAIN_BIN/clang"
CLANGXX_BIN_BASE="$TOOLCHAIN_BIN/clang++"

C_COMPILER="$(resolve_tool_path "$C_COMPILER_BASE" || true)"
CXX_COMPILER="$(resolve_tool_path "$CXX_COMPILER_BASE" || true)"
AR_TOOL="$(resolve_tool_path "$AR_TOOL_BASE" || true)"
CLANG_BIN="$(resolve_tool_path "$CLANG_BIN_BASE" || true)"
CLANGXX_BIN="$(resolve_tool_path "$CLANGXX_BIN_BASE" || true)"

[[ -n "$C_COMPILER" ]] || { echo "Compiler not found: ${C_COMPILER_BASE}(.cmd/.exe)" >&2; exit 1; }
[[ -n "$CXX_COMPILER" ]] || { echo "Compiler not found: ${CXX_COMPILER_BASE}(.cmd/.exe)" >&2; exit 1; }
[[ -n "$AR_TOOL" ]] || { echo "Archiver not found: ${AR_TOOL_BASE}(.cmd/.exe)" >&2; exit 1; }
[[ -n "$CLANG_BIN" ]] || { echo "Clang binary not found: ${CLANG_BIN_BASE}(.cmd/.exe)" >&2; exit 1; }
[[ -n "$CLANGXX_BIN" ]] || { echo "Clang++ binary not found: ${CLANGXX_BIN_BASE}(.cmd/.exe)" >&2; exit 1; }

mkdir -p "$PREFIX"

WRAPPER_BIN="$SCRIPT_DIR/.android-toolchain/$ABI-api$API/bin"
mkdir -p "$WRAPPER_BIN"

cat > "$WRAPPER_BIN/clang" <<EOF
#!/usr/bin/env bash
exec "$CLANG_BIN" --target=${TARGET_TRIPLE}${API} "\$@"
EOF

cat > "$WRAPPER_BIN/clang++" <<EOF
#!/usr/bin/env bash
exec "$CLANGXX_BIN" --target=${TARGET_TRIPLE}${API} "\$@"
EOF

cat > "$WRAPPER_BIN/ar" <<EOF
#!/usr/bin/env bash
exec "$AR_TOOL" "\$@"
EOF

chmod +x "$WRAPPER_BIN/clang" "$WRAPPER_BIN/clang++" "$WRAPPER_BIN/ar"

export PATH="$WRAPPER_BIN:$TOOLCHAIN_BIN:$PATH"

echo "Android build configuration:"
echo "  NDK_ROOT:      $NDK_ROOT"
echo "  HOST_TAG:      $HOST_TAG"
echo "  ABI:           $ABI"
echo "  API:           $API"
echo "  TARGET_TRIPLE: $TARGET_TRIPLE"
echo "  PREFIX:        $PREFIX"
echo "  MODE:          $MODE"
echo "  STD:           $STD"
echo "  PACKAGE:       $PACKAGE"
echo "  PACKAGE_DIR:   $PACKAGE_DIR"
echo

cd "$SCRIPT_DIR"

if [[ "$CLEAN" == "1" ]]; then
  python3 ./waf clean || true
fi

python3 ./waf configure \
  --mode="$MODE" \
  --std="$STD" \
  --cross-compile-android \
  --lightweight= \
  --fft=KISS \
  --ignore-algos="$IGNORE_ALGOS" \
  --prefix="$PREFIX"

python3 ./waf
python3 ./waf install

if [[ "$PACKAGE" == "1" ]]; then
  package_android_artifacts
fi

echo
echo "Build completed."
echo "Installed to: $PREFIX"
