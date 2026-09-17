#!/usr/bin/env bash
#
# 构建 ffmpeg-kit-next 的 Android AAR。
#
# ffmpeg-kit-next 只发布源码、不发布二进制制品，因此 FFmpegX 在编译前必须先构建出
# 这个 AAR。产物是一个本地 Maven 仓库，由 settings.gradle.kts 解析。
#
# 启用的外部库不在本文件里，见同目录的 ffmpeg-kit-next-libs.txt（唯一事实来源）。
#
# 环境要求：Linux / macOS / WSL2。Windows 原生环境无法构建 Android 目标。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SOURCE_DIR="${PROJECT_ROOT}/../ffmpeg-kit-next"
LIBS_FILE="${SCRIPT_DIR}/ffmpeg-kit-next-libs.txt"
API_LEVEL="24"
ABIS="arm64-v8a,armeabi-v7a,x86_64"
EXTRA_LIBS=""
ENABLE_GPL="yes"
BUILD_MODE="nix"
NIX_PROFILE="android-r27d"

# 上游支持的 Android 架构名（与 Android 侧的 ABI 名不同）
ALL_UPSTREAM_ARCHS="arm-v7a arm-v7a-neon arm64-v8a x86 x86_64"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
构建 ffmpeg-kit-next 的 Android AAR。

用法：
  ./scripts/build-ffmpeg-kit-next.sh [选项]

选项：
  --source <dir>   源码目录，默认 ../ffmpeg-kit-next
  --api <level>    minSdk，需与 app/build.gradle.kts 一致，默认 24
  --abis <list>    要构建的 ABI，默认 arm64-v8a,armeabi-v7a,x86_64
  --with <libs>    在清单之外追加外部库（逗号分隔）
  --no-gpl         不启用 GPL 组件，将失去 libx264 / libx265
  --no-nix         不使用 Nix，改用环境里已配置好的 SDK / NDK r27d
  -h, --help       显示本帮助

外部库清单见 scripts/ffmpeg-kit-next-libs.txt。
EOF
  exit 0
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --source)   SOURCE_DIR="$2"; shift 2 ;;
    --source=*) SOURCE_DIR="${1#*=}"; shift ;;
    --api)      API_LEVEL="$2"; shift 2 ;;
    --api=*)    API_LEVEL="${1#*=}"; shift ;;
    --abis)     ABIS="$2"; shift 2 ;;
    --abis=*)   ABIS="${1#*=}"; shift ;;
    --with)     EXTRA_LIBS="$2"; shift 2 ;;
    --with=*)   EXTRA_LIBS="${1#*=}"; shift ;;
    --no-gpl)   ENABLE_GPL="no"; shift ;;
    --no-nix)   BUILD_MODE="plain"; shift ;;
    -h|--help)  usage ;;
    *)          die "未知选项：$1（用 --help 查看用法）" ;;
  esac
done

# ------------------------------------------------------------------ 前置校验 ----

[ -d "${SOURCE_DIR}" ] || die "找不到 ffmpeg-kit-next 源码目录：${SOURCE_DIR}
请先克隆：git clone https://github.com/arthenica/ffmpeg-kit-next.git \"${SOURCE_DIR}\""

[ -f "${SOURCE_DIR}/nix-android.sh" ] || die "${SOURCE_DIR} 不是 ffmpeg-kit-next 源码（缺少 nix-android.sh）"

[ -f "${LIBS_FILE}" ] || die "缺少外部库清单：${LIBS_FILE}"

case "$(uname -s)" in
  Linux|Darwin) ;;
  MINGW*|MSYS*|CYGWIN*)
    die "Windows 原生环境无法构建 Android 目标，请改用 WSL2 / Linux / macOS。" ;;
  *) warn "未识别的主机系统：$(uname -s)，继续尝试" ;;
esac

# -------------------------------------------------------------- 组装构建参数 ----

to_upstream_arch() {
  case "$1" in
    arm64-v8a)        echo "arm64-v8a" ;;
    armeabi-v7a)      echo "arm-v7a" ;;
    armeabi-v7a-neon) echo "arm-v7a-neon" ;;
    x86)              echo "x86" ;;
    x86_64)           echo "x86_64" ;;
    *)                echo "" ;;
  esac
}

WANTED=""
IFS=',' read -r -a ABI_ARR <<< "${ABIS}"
for abi in "${ABI_ARR[@]}"; do
  abi="$(echo "${abi}" | xargs)"
  [ -n "${abi}" ] || continue
  up="$(to_upstream_arch "${abi}")"
  [ -n "${up}" ] || die "不支持的 ABI：${abi}（可选 arm64-v8a, armeabi-v7a, x86, x86_64）"
  WANTED="${WANTED}${WANTED:+ }${up}"
done
[ -n "${WANTED}" ] || die "至少需要一个 ABI"

# 只保留请求的架构，其余用 --disable-arch-* 关掉
ARCH_FLAGS=()
for arch in ${ALL_UPSTREAM_ARCHS}; do
  keep="no"
  for want in ${WANTED}; do
    [ "${arch}" = "${want}" ] && keep="yes" && break
  done
  [ "${keep}" = "no" ] && ARCH_FLAGS+=("--disable-arch-${arch}")
done

LIB_FLAGS=()
LIBS=""
while IFS= read -r line || [ -n "${line}" ]; do
  line="${line%%#*}"
  # 清单行形如 <库名> 或 <库名>=<符号>，这里只取库名
  lib="${line%%=*}"
  lib="$(echo "${lib}" | xargs)"
  [ -n "${lib}" ] || continue
  if [ "${lib}" = "gpl" ]; then
    [ "${ENABLE_GPL}" = "yes" ] && LIB_FLAGS+=("--enable-gpl")
    continue
  fi
  LIBS="${LIBS}${LIBS:+ }${lib}"
  LIB_FLAGS+=("--enable-lib-${lib}")
done < "${LIBS_FILE}"

if [ -n "${EXTRA_LIBS}" ]; then
  IFS=',' read -r -a EXTRA_ARR <<< "${EXTRA_LIBS}"
  for lib in "${EXTRA_ARR[@]}"; do
    lib="$(echo "${lib}" | xargs)"
    [ -n "${lib}" ] || continue
    LIB_FLAGS+=("--enable-lib-${lib}")
  done
fi

log "源码目录 : ${SOURCE_DIR}"
log "minSdk   : ${API_LEVEL}"
log "目标架构 : ${ABIS}（上游名：${WANTED}）"
log "跳过架构 : ${ARCH_FLAGS[*]:-无}"
log "外部库   : ${LIBS}"
log "构建方式 : ${BUILD_MODE}"

# ------------------------------------------------------------------ 执行构建 ----

cd "${SOURCE_DIR}"

if [ "${BUILD_MODE}" = "nix" ]; then
  command -v nix >/dev/null 2>&1 || die "未找到 nix。安装见 https://nixos.org/download，或改用 --no-nix"
  # ARCH_FLAGS 可能为空数组，bash 3.2 下 "${arr[@]}" 配合 set -u 会报错，
  # 因此用 ${arr[@]+"${arr[@]}"} 这种可移植写法。
  ./nix-android.sh \
    -p "${NIX_PROFILE}" \
    --api-level="${API_LEVEL}" \
    ${ARCH_FLAGS[@]+"${ARCH_FLAGS[@]}"} \
    "${LIB_FLAGS[@]}"
else
  [ -n "${ANDROID_SDK_ROOT:-}" ] || die "未设置 ANDROID_SDK_ROOT"
  [ -n "${ANDROID_NDK_ROOT:-}" ] || die "未设置 ANDROID_NDK_ROOT（需要 NDK r27d）"
  ./android.sh \
    --api-level="${API_LEVEL}" \
    ${ARCH_FLAGS[@]+"${ARCH_FLAGS[@]}"} \
    "${LIB_FLAGS[@]}"
fi

# ------------------------------------------------------------------ 校验产物 ----

MAVEN_REPO="${SOURCE_DIR}/prebuilt/bundle-android-aar-${API_LEVEL}-maven"
if [ ! -d "${MAVEN_REPO}" ]; then
  MAVEN_REPO="$(find "${SOURCE_DIR}/prebuilt" -maxdepth 1 -type d -name 'bundle-android-aar-*-maven' 2>/dev/null | head -1 || true)"
fi
[ -n "${MAVEN_REPO}" ] && [ -d "${MAVEN_REPO}" ] || die "未找到 Maven 产物目录（prebuilt/bundle-android-aar-*-maven）"

AAR="$(find "${MAVEN_REPO}" -name '*.aar' | head -1 || true)"
[ -n "${AAR}" ] && [ -f "${AAR}" ] || die "在 ${MAVEN_REPO} 下未找到 .aar"

VERSION="$(basename "${AAR}" .aar | sed 's/^ffmpeg-kit-next-//')"

log "构建完成"
log "  AAR 版本   : ${VERSION}"
log "  Maven 仓库 : ${MAVEN_REPO}"
echo
echo "请确认以下两处配置与该产物一致："
echo "  gradle.properties: ffmpegx.ffmpegKitNextRepo=${MAVEN_REPO}"
echo "  gradle/libs.versions.toml: ffmpegKitNext = \"${VERSION}\""
