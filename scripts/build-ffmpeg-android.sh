#!/usr/bin/env bash
# =============================================================================
#  FFmpegX —— 为 Android 交叉编译 FFmpeg（含 MediaCodec / Vulkan 硬件加速）
#
#  用法：
#     ./scripts/build-ffmpeg-android.sh                      # 默认 standard profile + 主流 ABI
#     ./scripts/build-ffmpeg-android.sh --profile minimal    # 只编 FFmpeg 本体，最快
#     ./scripts/build-ffmpeg-android.sh --abis arm64-v8a     # 只编 arm64
#     ./scripts/build-ffmpeg-android.sh --jobs 8 --api 24
#
#  产物（全部落在 app/src/main/ 下，Gradle 会自动使用）：
#     cpp/ffmpeg/include/**       头文件 + config.h
#     cpp/fftools/**              FFmpeg 的 CLI 源码（ffmpeg.c / ffprobe.c 等）
#     cpp/compat/**               cmdutils.c 需要的兼容头
#     jniLibs/<abi>/*.so          FFmpeg 共享库（已去掉版本号后缀）
#
#  运行环境：Linux / macOS / WSL（Windows 原生 bash 缺少部分工具，建议 WSL）
#  前置条件：Android NDK r26+、git、make、pkg-config、curl
# =============================================================================

set -euo pipefail

# ------------------------------------------------------------------ 默认参数 ---
FFMPEG_VERSION="${FFMPEG_VERSION:-7.1}"
ANDROID_API="${ANDROID_API:-24}"
PROFILE="${PROFILE:-standard}"                 # minimal | standard | full
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
ABIS_DEFAULT="arm64-v8a armeabi-v7a x86_64"
ABIS="$ABIS_DEFAULT"
ENABLE_NETWORK="${ENABLE_NETWORK:-0}"
CLEAN=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="${PROJECT_DIR}/app/src/main"
CPP_DIR="${APP_DIR}/cpp"
JNI_DIR="${APP_DIR}/jniLibs"
WORK_DIR="${PROJECT_DIR}/build-ffmpeg"
SRC_DIR="${WORK_DIR}/ffmpeg-${FFMPEG_VERSION}"
DEPS_PREFIX_BASE="${WORK_DIR}/deps"

# ------------------------------------------------------------------ 参数解析 ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile)   PROFILE="$2"; shift 2 ;;
        --abis)      ABIS="$2"; shift 2 ;;
        --api)       ANDROID_API="$2"; shift 2 ;;
        --jobs|-j)   JOBS="$2"; shift 2 ;;
        --version)   FFMPEG_VERSION="$2"; shift 2 ;;
        --network)   ENABLE_NETWORK=1; shift ;;
        --clean)     CLEAN=1; shift ;;
        -h|--help)
            sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# ------------------------------------------------------------------ 输出样式 ---
C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYA=$'\033[36m'; C_RST=$'\033[0m'
log()  { echo "${C_CYA}[FFmpegX]${C_RST} $*"; }
ok()   { echo "${C_GRN}[  OK  ]${C_RST} $*"; }
warn() { echo "${C_YEL}[ WARN ]${C_RST} $*"; }
die()  { echo "${C_RED}[ FAIL ]${C_RST} $*" >&2; exit 1; }

# ------------------------------------------------------------------ 环境检测 ---
detect_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
        NDK="${ANDROID_NDK_HOME}"; return
    fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
        NDK="${ANDROID_NDK_ROOT}"; return
    fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        local cand
        cand="$(ls -d "${ANDROID_HOME}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
        [[ -n "$cand" ]] && { NDK="$cand"; return; }
    fi
    for base in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
        local cand
        cand="$(ls -d "${base}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
        [[ -n "$cand" ]] && { NDK="$cand"; return; }
    done
    die "找不到 Android NDK。请设置 ANDROID_NDK_HOME 或 ANDROID_HOME。"
}

detect_host_tag() {
    case "$(uname -s)" in
        Linux)  echo "linux-x86_64" ;;
        Darwin) echo "darwin-x86_64" ;;
        *)      die "不支持的主机系统 $(uname -s)，请在 Linux / macOS / WSL 下运行。" ;;
    esac
}

# ------------------------------------------------------------------ ABI 映射 ---
abi_arch() {
    case "$1" in
        arm64-v8a)   echo "aarch64" ;;
        armeabi-v7a) echo "arm" ;;
        x86_64)      echo "x86_64" ;;
        *) die "不支持的 ABI: $1" ;;
    esac
}
abi_cpu() {
    case "$1" in
        arm64-v8a)   echo "armv8-a" ;;
        armeabi-v7a) echo "armv7-a" ;;
        x86_64)      echo "x86-64" ;;
    esac
}
abi_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
    esac
}

# =============================================================================
#  1. 拉取 FFmpeg 源码
# =============================================================================
fetch_ffmpeg() {
    if [[ -f "${SRC_DIR}/configure" ]]; then
        log "FFmpeg 源码已存在：${SRC_DIR}"
        return
    fi
    mkdir -p "${WORK_DIR}"
    local tarball="${WORK_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    if [[ ! -f "$tarball" ]]; then
        log "下载 FFmpeg ${FFMPEG_VERSION} ..."
        local urls=(
            "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
            "https://git.ffmpeg.org/gitweb/ffmpeg.git/snapshot/ffmpeg-${FFMPEG_VERSION}.tar.xz"
        )
        local done_dl=0
        for u in "${urls[@]}"; do
            if curl -fL --retry 3 --connect-timeout 20 -o "$tarball" "$u"; then done_dl=1; break; fi
            warn "下载失败，尝试下一个源：$u"
        done
        [[ $done_dl -eq 1 ]] || die "FFmpeg 源码下载失败，请手动下载后放到 ${tarball}"
    fi
    log "解压 FFmpeg 源码 ..."
    tar -xf "$tarball" -C "${WORK_DIR}"
    [[ -f "${SRC_DIR}/configure" ]] || die "解压后未找到 configure，检查版本号是否正确"
    ok "FFmpeg 源码就绪"
}

# =============================================================================
#  2. 第三方依赖（profile != minimal 时）
# =============================================================================
build_one_dep() {
    local name="$1" url="$2" configure_extra="$3" abi="$4" prefix="$5"
    local dep_src="${WORK_DIR}/dep-src/${name}"
    local marker="${prefix}/.done-${name}"

    [[ -f "$marker" ]] && { ok "${name} [${abi}] 已构建，跳过"; return; }

    local tarball="${WORK_DIR}/${name}.tar.gz"
    if [[ ! -d "$dep_src" ]]; then
        mkdir -p "${WORK_DIR}/dep-src"
        [[ -f "$tarball" ]] || { log "下载 ${name} ..."; curl -fL --retry 3 -o "$tarball" "$url" || die "${name} 下载失败"; }
        tar -xf "$tarball" -C "${WORK_DIR}/dep-src"
        dep_src="$(ls -d "${WORK_DIR}/dep-src/${name}"* 2>/dev/null | head -1)"
    fi

    local arch triple host_tag
    arch="$(abi_arch "$abi")"
    triple="$(abi_triple "$abi")"
    host_tag="$(detect_host_tag)"
    local toolchain="${NDK}/toolchains/llvm/prebuilt/${host_tag}"
    local cc="${toolchain}/bin/${triple}${ANDROID_API}-clang"
    local cxx="${toolchain}/bin/${triple}${ANDROID_API}-clang++"

    log "构建 ${name} [${abi}] ..."
    pushd "$dep_src" >/dev/null

    export CC="$cc" CXX="$cxx" AR="${toolchain}/bin/llvm-ar" \
           RANLIB="${toolchain}/bin/llvm-ranlib" STRIP="${toolchain}/bin/llvm-strip" \
           NM="${toolchain}/bin/llvm-nm"

    local cpu_flag=""
    [[ "$arch" == "aarch64" ]] && cpu_flag="--enable-neon"
    [[ "$arch" == "arm"     ]] && cpu_flag="--enable-neon --cpu=armv7-a"

    # shellcheck disable=SC2086
    ./configure \
        --prefix="$prefix" \
        --host="${triple}" \
        --sysroot="${toolchain}/sysroot" \
        --enable-static \
        --disable-shared \
        --disable-programs \
        --disable-doc \
        --disable-cli \
        $cpu_flag \
        $configure_extra \
        > "${WORK_DIR}/log-${name}-${abi}.txt" 2>&1 \
        || { tail -40 "${WORK_DIR}/log-${name}-${abi}.txt"; popd >/dev/null; die "${name} configure 失败"; }

    make -j"$JOBS" >> "${WORK_DIR}/log-${name}-${abi}.txt" 2>&1 \
        || { tail -40 "${WORK_DIR}/log-${name}-${abi}.txt"; popd >/dev/null; die "${name} 编译失败"; }
    make install >> "${WORK_DIR}/log-${name}-${abi}.txt" 2>&1 || true

    popd >/dev/null
    touch "$marker"
    ok "${name} [${abi}] 完成"
}

build_deps() {
    local abi="$1"
    local prefix="${DEPS_PREFIX_BASE}/${abi}"
    mkdir -p "$prefix"

    # x264：软件 H.264 编码（硬编不可用时的兜底，质量远好于 FFmpeg 内置 mpeg4）
    build_one_dep x264 \
        "https://code.videolan.org/videolan/x264/-/archive/stable/x264-stable.tar.gz" \
        "--enable-pic --disable-asm" "$abi" "$prefix"

    # LAME：MP3 编码
    build_one_dep lame \
        "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz" \
        "--enable-pic --disable-frontend" "$abi" "$prefix"

    # Opus：低码率音频首选
    build_one_dep opus \
        "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz" \
        "--enable-pic --disable-extra-programs --disable-doc" "$abi" "$prefix"

    # FreeType + HarfBuzz + FriBidi + libass：字幕烧录（subtitles/ass 滤镜）
    build_one_dep freetype \
        "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.gz" \
        "--enable-pic --without-bzip2 --without-png --without-harfbuzz --without-brotli" \
        "$abi" "$prefix"

    if [[ "$PROFILE" == "full" ]]; then
        build_one_dep fribidi \
            "https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz" \
            "--enable-pic" "$abi" "$prefix"
        build_one_dep harfbuzz \
            "https://github.com/harfbuzz/harfbuzz/releases/download/10.0.1/harfbuzz-10.0.1.tar.xz" \
            "--enable-pic --with-freetype=yes --without-glib --without-gobject --without-cairo --without-icu" \
            "$abi" "$prefix"
        build_one_dep libass \
            "https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.xz" \
            "--enable-pic --disable-fontconfig --disable-require-system-font-provider" \
            "$abi" "$prefix"
    fi

    echo "$prefix"
}

# =============================================================================
#  3. 配置并编译 FFmpeg
# =============================================================================
ffmpeg_configure_flags() {
    local abi="$1" deps_prefix="$2"
    local arch triple cpu
    arch="$(abi_arch "$abi")"; triple="$(abi_triple "$abi")"; cpu="$(abi_cpu "$abi")"

    local host_tag toolchain
    host_tag="$(detect_host_tag)"
    toolchain="${NDK}/toolchains/llvm/prebuilt/${host_tag}"

    local extra_cflags="-O3 -fPIC -D__ANDROID_API__=${ANDROID_API} -ffunction-sections -fdata-sections"
    local extra_ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384"
    local deps_flags=""

    if [[ "$PROFILE" != "minimal" ]]; then
        extra_cflags+=" -I${deps_prefix}/include"
        extra_ldflags+=" -L${deps_prefix}/lib"
        deps_flags="
            --enable-libx264
            --enable-libmp3lame
            --enable-libopus
            --enable-libfreetype
            --enable-libfontconfig=no
        "
        if [[ "$PROFILE" == "full" ]]; then
            deps_flags+="
                --enable-libass
                --enable-libfribidi
                --enable-libharfbuzz
            "
        fi
    fi

    cat <<EOF
--prefix=${WORK_DIR}/install/${abi}
--target-os=android
--arch=${arch}
--cpu=${cpu}
--enable-cross-compile
--sysroot=${toolchain}/sysroot
--cross-prefix=${toolchain}/bin/llvm-
--cc=${toolchain}/bin/${triple}${ANDROID_API}-clang
--cxx=${toolchain}/bin/${triple}${ANDROID_API}-clang++
--ar=${toolchain}/bin/llvm-ar
--nm=${toolchain}/bin/llvm-nm
--ranlib=${toolchain}/bin/llvm-ranlib
--strip=${toolchain}/bin/llvm-strip
--pkg-config=$(command -v pkg-config || echo false)

--enable-shared
--disable-static
--enable-pic
--enable-small
--enable-optimizations
--disable-programs
--disable-doc
--disable-htmlpages
--disable-manpages
--disable-podpages
--disable-txtpages
--disable-debug
--disable-avdevice
--disable-postproc
--disable-symver
--disable-stripping

--enable-jni
--enable-mediacodec
--enable-vulkan

# MediaCodec 的 JNI 桥接需要 libandroid（NDK 里是 stub，运行期由系统提供实现）
--extra-libs="-landroid"

--enable-avcodec
--enable-avformat
--enable-avfilter
--enable-swresample
--enable-swscale
--enable-decoder=h264_mediacodec
--enable-decoder=hevc_mediacodec
--enable-decoder=av1_mediacodec
--enable-decoder=vp9_mediacodec
--enable-decoder=mpeg4_mediacodec
--enable-encoder=h264_mediacodec
--enable-encoder=hevc_mediacodec
--enable-encoder=av1_mediacodec
--enable-encoder=vp9_mediacodec
--enable-encoder=mpeg4_mediacodec

--enable-filter=scale_vulkan
--enable-filter=overlay_vulkan
--enable-filter=transpose_vulkan

$([[ "$ENABLE_NETWORK" == "1" ]] && echo "--enable-network" || echo "--disable-network")
${deps_flags}
--extra-cflags="${extra_cflags}"
--extra-cxxflags="${extra_cflags}"
--extra-ldflags="${extra_ldflags}"
EOF
}

patch_slibname() {
    # 去掉 .so 的版本号后缀（libavcodec.so.61 -> libavcodec.so）。
    # 原因：Android 只把以 .so 结尾的文件当作 native lib 打包，
    # 而 FFmpeg 默认产出的 SONAME 带主版本号，会导致 DT_NEEDED 找不到文件。
    local cfg="ffbuild/config.mak"
    [[ -f "$cfg" ]] || die "找不到 $cfg"
    sed -i.bak -E \
        -e 's|^SLIBNAME_WITH_MAJOR=.*|SLIBNAME_WITH_MAJOR=$(SLIBNAME)|' \
        -e 's|^SLIBNAME_WITH_VERSION=.*|SLIBNAME_WITH_VERSION=$(SLIBNAME)|' \
        -e 's|^SLIB_INSTALL_NAME=.*|SLIB_INSTALL_NAME=$(SLIBNAME)|' \
        -e 's|^SLIB_INSTALL_LINKS=.*|SLIB_INSTALL_LINKS=|' \
        "$cfg"
}

build_ffmpeg_abi() {
    local abi="$1"
    local deps_prefix="${DEPS_PREFIX_BASE}/${abi}"
    local build_dir="${WORK_DIR}/build-${abi}"
    local install_dir="${WORK_DIR}/install/${abi}"

    log "============================================================"
    log " 构建 FFmpeg ${FFMPEG_VERSION}  ABI=${abi}  profile=${PROFILE}"
    log "============================================================"

    rm -rf "$build_dir"; mkdir -p "$build_dir"
    # shellcheck disable=SC2046
    ( cd "$build_dir" && "$SRC_DIR/configure" $(ffmpeg_configure_flags "$abi" "$deps_prefix") ) \
        > "${WORK_DIR}/log-configure-${abi}.txt" 2>&1 \
        || { tail -60 "${WORK_DIR}/log-configure-${abi}.txt"; die "configure 失败 [${abi}]"; }

    ( cd "$build_dir" && patch_slibname )

    log "编译中（-j${JOBS}）... 日志：${WORK_DIR}/log-make-${abi}.txt"
    ( cd "$build_dir" && make -j"$JOBS" ) > "${WORK_DIR}/log-make-${abi}.txt" 2>&1 \
        || { tail -60 "${WORK_DIR}/log-make-${abi}.txt"; die "编译失败 [${abi}]"; }
    ( cd "$build_dir" && make install ) >> "${WORK_DIR}/log-make-${abi}.txt" 2>&1 \
        || { tail -40 "${WORK_DIR}/log-make-${abi}.txt"; die "install 失败 [${abi}]"; }

    ok "FFmpeg [${abi}] 编译完成"

    # ---- 拷贝 .so 到 jniLibs ----
    mkdir -p "${JNI_DIR}/${abi}"
    local copied=0
    for so in "${install_dir}"/lib/*.so; do
        [[ -e "$so" ]] || continue
        # 只保留 .so 结尾的真实文件（跳过带版本号的与软链）
        [[ "$(basename "$so")" == *.so ]] || continue
        cp -f "$so" "${JNI_DIR}/${abi}/"
        copied=$((copied + 1))
    done
    [[ $copied -gt 0 ]] || die "没有拷贝到任何 .so，请检查 ${install_dir}/lib"
    ok "拷贝 ${copied} 个共享库 -> app/src/main/jniLibs/${abi}/"
}

# =============================================================================
#  4. 同步头文件与 fftools 源码
# =============================================================================
sync_headers_and_fftools() {
    local build_dir="${WORK_DIR}/build-arm64-v8a"
    local install_dir="${WORK_DIR}/install/arm64-v8a"

    # arm64 作为「主」构建；若只编了其它 ABI，退而求其次
    [[ -d "$build_dir" ]] || build_dir="${WORK_DIR}/build-$(echo "$ABIS" | awk '{print $1}')"
    [[ -d "$install_dir" ]] || install_dir="${WORK_DIR}/install/$(echo "$ABIS" | awk '{print $1}')"

    log "同步头文件 -> cpp/ffmpeg/include/"
    mkdir -p "${CPP_DIR}/ffmpeg/include"
    if [[ -d "${install_dir}/include" ]]; then
        cp -rf "${install_dir}/include/." "${CPP_DIR}/ffmpeg/include/"
    fi
    # config.h / config_components.h 不会被 make install 安装，需要手动带过来
    for h in config.h config_components.h config.asm; do
        [[ -f "${build_dir}/${h}" ]] && cp -f "${build_dir}/${h}" "${CPP_DIR}/ffmpeg/include/"
    done

    log "同步 fftools 源码 -> cpp/fftools/"
    rm -rf "${CPP_DIR}/fftools"
    mkdir -p "${CPP_DIR}/fftools"
    cp -rf "${SRC_DIR}/fftools/." "${CPP_DIR}/fftools/"
    # 移除 CMake 不需要的构建脚本，避免被误当作源码
    find "${CPP_DIR}/fftools" -name 'Makefile' -delete 2>/dev/null || true
    find "${CPP_DIR}/fftools" -name '*.mak'    -delete 2>/dev/null || true

    log "同步 compat 头 -> cpp/compat/"
    rm -rf "${CPP_DIR}/compat"
    if [[ -d "${SRC_DIR}/compat" ]]; then
        mkdir -p "${CPP_DIR}/compat"
        # 只要头文件，.c 已经编进 FFmpeg 库里了
        find "${SRC_DIR}/compat" -maxdepth 1 -name '*.h' -exec cp -f {} "${CPP_DIR}/compat/" \;
    fi

    ok "源码与头文件同步完成"
}

# =============================================================================
#  main
# =============================================================================
main() {
    detect_ndk
    log "NDK      : ${NDK}"
    log "FFmpeg   : ${FFMPEG_VERSION}"
    log "Profile  : ${PROFILE}"
    log "ABIs     : ${ABIS}"
    log "minSdk   : ${ANDROID_API}"
    log "Jobs     : ${JOBS}"

    if [[ $CLEAN -eq 1 ]]; then
        log "清理构建目录 ..."
        rm -rf "${WORK_DIR}" "${JNI_DIR}" "${CPP_DIR}/ffmpeg" "${CPP_DIR}/fftools" "${CPP_DIR}/compat"
    fi

    command -v curl >/dev/null || die "缺少 curl"
    command -v make >/dev/null || die "缺少 make"

    fetch_ffmpeg

    local abi
    for abi in $ABIS; do
        if [[ "$PROFILE" != "minimal" ]]; then
            build_deps "$abi"
        fi
        build_ffmpeg_abi "$abi"
    done

    sync_headers_and_fftools

    echo
    ok "全部完成 🎉"
    echo
    echo "  下一步："
    echo "    1) 在 Android Studio 里 Sync Project（会自动识别 cpp/CMakeLists.txt）"
    echo "    2) ./gradlew assembleDebug"
    echo
    echo "  产物目录："
    echo "    ${JNI_DIR}/<abi>/"
    echo "    ${CPP_DIR}/ffmpeg/include/"
    echo
}

main "$@"
