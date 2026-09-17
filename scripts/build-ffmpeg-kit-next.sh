#!/usr/bin/env bash
#
# 构建 ffmpeg-kit-next 的 Android AAR（FFmpegX 的唯一 FFmpeg 核心）
#
# ffmpeg-kit-next 不发布 Maven 制品，必须自己构建。本脚本把上游那套
# Nix/非 Nix 构建流程包一层，产出可直接被 FFmpegX 消费的本地 Maven 仓库。
#
# 用法：
#   ./scripts/build-ffmpeg-kit-next.sh [选项]
#
# 选项：
#   --source <dir>       ffmpeg-kit-next 源码目录（默认 ../ffmpeg-kit-next）
#   --api <level>        minSdk，需与 FFmpegX 的 minSdk 一致（默认 24）
#   --abis <list>        只构建这些 ABI（默认 arm64-v8a,armeabi-v7a,x86_64）
#   --no-gpl             不启用 GPL 库（会失去 libx264/libx265，全软编码策略将失效）
#   --with <libs>        额外启用外部库（逗号分隔），如 x264,x265,libass,fontconfig
#   --prefab             额外产出 Prefab 载荷（FFmpegX 用不到，默认关闭）
#   --use-nix            走 Nix 工作流（默认；需要已安装 Nix）
#   --no-nix             不走 Nix，依赖环境里已配好的 SDK/NDK
#   -h, --help           显示帮助
#
# 前置要求（按所选工作流二选一）：
#   Nix 工作流（推荐）  安装 Nix；NixOS 不能作为构建主机
#   非 Nix 工作流       自行准备 Android SDK + NDK r27d + CMake + 构建工具
#
# 产物：
#   <source>/prebuilt/bundle-android-aar-<api>-maven/
#     └── com/arthenica/ffmpeg-kit-next/<version>/ffmpeg-kit-next-<version>.aar
#
# 之后在 FFmpegX 的 gradle.properties 里指向该目录：
#   ffmpegx.ffmpegKitNextRepo=../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FFMPEGX_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SOURCE_DIR="${FFMPEGX_ROOT}/../ffmpeg-kit-next"
API_LEVEL="24"
ABIS="arm64-v8a,armeabi-v7a,x86_64"
ENABLE_GPL="yes"
EXTRA_LIBS=""
USE_PREFAB=""
BUILD_MODE="nix"

# ffmpeg-kit-next 的 Nix profile（其 README 推荐的 Android 构建配置，含 NDK r27d）
NIX_PROFILE="android-r27d"

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --source)     SOURCE_DIR="$2"; shift 2 ;;
    --source=*)   SOURCE_DIR="${1#*=}"; shift ;;
    --api)        API_LEVEL="$2"; shift 2 ;;
    --api=*)      API_LEVEL="${1#*=}"; shift ;;
    --abis)       ABIS="$2"; shift 2 ;;
    --abis=*)     ABIS="${1#*=}"; shift ;;
    --no-gpl)     ENABLE_GPL="no"; shift ;;
    --with)       EXTRA_LIBS="$2"; shift 2 ;;
    --with=*)     EXTRA_LIBS="${1#*=}"; shift ;;
    --prefab)     USE_PREFAB="--prefab"; shift ;;
    --use-nix)    BUILD_MODE="nix"; shift ;;
    --no-nix)     BUILD_MODE="plain"; shift ;;
    -h|--help)    usage ;;
    *)            die "未知选项：$1（用 --help 查看用法）" ;;
  esac
done

# ------------------------------------------------------------------ 前置校验 ----

log "FFmpegX 根目录   : ${FFMPEGX_ROOT}"
log "ffmpeg-kit-next : ${SOURCE_DIR}"

[ -d "${SOURCE_DIR}" ] || die "找不到 ffmpeg-kit-next 源码目录：${SOURCE_DIR}
请先克隆：
  git clone https://github.com/arthenica/ffmpeg-kit-next.git \"${SOURCE_DIR}\""

[ -f "${SOURCE_DIR}/nix-android.sh" ] || die "${SOURCE_DIR} 看起来不是 ffmpeg-kit-next 源码（缺 nix-android.sh）"

case "$(uname -s)" in
  Linux|Darwin) ;;
  MINGW*|MSYS*|CYGWIN*)
    die "Windows 原生环境不适合构建 ffmpeg-kit-next。
请使用 WSL2（Ubuntu）或 Linux/macOS 主机。
Windows 上上游只提供 windows.sh 构建 Windows 目标，无法产出 Android AAR。" ;;
  *) warn "未识别的主机系统：$(uname -s)，继续尝试" ;;
esac

# ------------------------------------------------------------------ 组装参数 ----

# ffmpeg-kit-next 用 --disable-arch-<name> 关闭架构。
# 它支持的 Android 架构名：arm-v7a / arm-v7a-neon / arm64-v8a / x86 / x86_64
ALL_ARCHS="arm-v7a arm-v7a-neon arm64-v8a x86 x86_64"

# 把用户给的 ABI 名映射成上游的架构名
to_upstream_arch() {
  case "$1" in
    arm64-v8a)     echo "arm64-v8a" ;;
    armeabi-v7a)   echo "arm-v7a" ;;
    armeabi-v7a-neon) echo "arm-v7a-neon" ;;
    x86)           echo "x86" ;;
    x86_64)        echo "x86_64" ;;
    *)             echo "" ;;
  esac
}

WANTED_ARCHS=""
IFS=',' read -r -a ABI_ARR <<< "${ABIS}"
for abi in "${ABI_ARR[@]}"; do
  abi="$(echo "$abi" | xargs)"   # trim
  [ -n "$abi" ] || continue
  up="$(to_upstream_arch "$abi")"
  [ -n "$up" ] || die "不支持的 ABI：$abi（可选：arm64-v8a,armeabi-v7a,x86,x86_64）"
  WANTED_ARCHS="${WANTED_ARCHS}${WANTED_ARCHS:+ }${up}"
done
[ -n "${WANTED_ARCHS}" ] || die "至少需要一个 ABI"

DISABLE_FLAGS=()
for arch in ${ALL_ARCHS}; do
  keep="no"
  for want in ${WANTED_ARCHS}; do
    [ "${arch}" = "${want}" ] && keep="yes" && break
  done
  if [ "${keep}" = "no" ]; then
    DISABLE_FLAGS+=("--disable-arch-${arch}")
  fi
done

# 默认库清单：**必须与 CI（.github/workflows/build.yml）保持一致**。
# 每一项都对应 App 里真实会生成的编码器参数，少一个就会有功能在真机上
# 直接报 "Error opening output files: Encoder not found"。
#
# ⚠️ android-media-codec 默认是 no，必须显式开启。不开的话 MediaCodec 硬编硬解
# 在 libavcodec 里根本不存在，而 App 会依据设备 MediaCodec 能力生成
# `-c:v h264_mediacodec` —— 于是每一个走「智能/速度优先」策略的任务都会失败。
DEFAULT_LIBS="android-media-codec lame opus libvorbis libvpx libass"

LIB_FLAGS=()
if [ "${ENABLE_GPL}" = "yes" ]; then
  # 不加 --enable-gpl 就没有 libx264/libx265，
  # FFmpegX 的「兼容优先」（全软编码）策略会因为找不到软件编码器而失效。
  LIB_FLAGS+=("--enable-gpl")
  DEFAULT_LIBS="x264 x265 ${DEFAULT_LIBS}"
fi

for lib in ${DEFAULT_LIBS}; do
  LIB_FLAGS+=("--enable-lib-${lib}")
done

if [ -n "${EXTRA_LIBS}" ]; then
  IFS=',' read -r -a EXTRA_ARR <<< "${EXTRA_LIBS}"
  for lib in "${EXTRA_ARR[@]}"; do
    lib="$(echo "$lib" | xargs)"
    [ -n "$lib" ] || continue
    LIB_FLAGS+=("--enable-lib-${lib}")
  done
fi

log "minSdk      : ${API_LEVEL}"
log "目标架构     : ${ABIS}（上游名：${WANTED_ARCHS}）"
log "跳过的架构   : ${DISABLE_FLAGS[*]:-无}"
log "GPL 库       : ${ENABLE_GPL}"
log "额外库       : ${EXTRA_LIBS:-无}"
log "构建工作流   : ${BUILD_MODE}"
echo

# ------------------------------------------------------------------ 执行构建 ----

cd "${SOURCE_DIR}"

if [ "${BUILD_MODE}" = "nix" ]; then
  command -v nix >/dev/null 2>&1 || die "未找到 nix 命令。
安装 Nix：https://nixos.org/download
或改用 --no-nix 并自行准备 SDK/NDK r27d。"

  log "列出可用的 Nix profiles（确认 ${NIX_PROFILE} 存在）"
  ./nix-android.sh --list-profiles || true

  log "开始构建（这一步会下载 FFmpeg 与各外部库源码，耗时较长）"
  ./nix-android.sh \
    -p "${NIX_PROFILE}" \
    --api-level="${API_LEVEL}" \
    "${DISABLE_FLAGS[@]}" \
    "${LIB_FLAGS[@]}" \
    ${USE_PREFAB}
else
  [ -n "${ANDROID_SDK_ROOT:-}" ] || die "未设置 ANDROID_SDK_ROOT"
  [ -n "${ANDROID_NDK_ROOT:-}" ] || die "未设置 ANDROID_NDK_ROOT（需要 NDK r27d）"

  log "开始构建（非 Nix 工作流）"
  ./android.sh \
    --api-level="${API_LEVEL}" \
    "${DISABLE_FLAGS[@]}" \
    "${LIB_FLAGS[@]}" \
    ${USE_PREFAB}
fi

# ------------------------------------------------------------------ 校验产物 ----

MAVEN_REPO="${SOURCE_DIR}/prebuilt/bundle-android-aar-${API_LEVEL}-maven"

if [ ! -d "${MAVEN_REPO}" ]; then
  # 上游在 --enable-gpl 时会换目录后缀，做一次兜底查找
  MAVEN_REPO="$(find "${SOURCE_DIR}/prebuilt" -maxdepth 1 -type d -name 'bundle-android-aar-*-maven' 2>/dev/null | head -1 || true)"
fi

[ -n "${MAVEN_REPO}" ] && [ -d "${MAVEN_REPO}" ] || die "构建似乎完成，但找不到 Maven 产物目录（prebuilt/bundle-android-aar-*-maven）"

AAR="$(find "${MAVEN_REPO}" -name '*.aar' | head -1 || true)"
[ -n "${AAR}" ] && [ -f "${AAR}" ] || die "在 ${MAVEN_REPO} 下没找到 .aar"

VERSION="$(basename "${AAR}" .aar | sed 's/^ffmpeg-kit-next-//')"

echo
log "构建成功"
log "  AAR 版本 : ${VERSION}"
log "  AAR 路径 : ${AAR}"
log "  Maven 仓库: ${MAVEN_REPO}"
echo
echo "接下来在 FFmpegX 的 gradle.properties 里设置："
echo "  ffmpegx.ffmpegKitNextRepo=${MAVEN_REPO}"
echo
echo "并确认 gradle/libs.versions.toml 中的 ffmpegKitNext 版本与之匹配："
echo "  ffmpegKitNext = \"${VERSION}\""
echo
echo "然后构建 APK："
echo "  ./gradlew assembleDebug"
