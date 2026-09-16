#!/usr/bin/env bash
# =============================================================================
#  FFmpegX 一键构建 APK
#
#  用法：
#     ./scripts/build-apk.sh                 # 构建 debug APK
#     ./scripts/build-apk.sh --release       # 构建 release APK（未签名）
#     ./scripts/build-apk.sh --abis arm64-v8a   # 只打一个 ABI，APK 从 ~124MB 降到 ~46MB
#     ./scripts/build-apk.sh --backend native   # 改用自研 JNI（需先跑 build-ffmpeg-android.sh）
#     ./scripts/build-apk.sh --test          # 先跑单元测试再打包
#
#  这个脚本会自动完成：
#     1. 定位 JDK（需要 17 及以上）
#     2. 下载并安装 Android SDK（cmdline-tools / platform / build-tools）
#     3. 下载 Gradle
#     4. 生成 Gradle Wrapper
#     5. 构建 APK 并复制到 dist/
#
#  所有工具链都放在项目下的 .toolchain/ 里，不污染系统环境，删掉即可重置。
#  已经存在的组件会跳过下载，所以可以反复运行。
#
#  运行环境：Git Bash（Windows）/ Linux / macOS
# =============================================================================

set -euo pipefail

# ------------------------------------------------------------------ 默认参数 ---
BUILD_TYPE="debug"
ABIS="${FFMPEGX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}"
BACKEND="${FFMPEGX_BACKEND:-kit}"
RUN_TESTS=0
GRADLE_VERSION="${GRADLE_VERSION:-8.14.3}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
PLATFORM="android-36"
BUILD_TOOLS="36.0.0"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --release)  BUILD_TYPE="release"; shift ;;
        --debug)    BUILD_TYPE="debug"; shift ;;
        --abis)     ABIS="$2"; shift 2 ;;
        --backend)  BACKEND="$2"; shift 2 ;;
        --test)     RUN_TESTS=1; shift ;;
        -h|--help)
            sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# ------------------------------------------------------------------ 输出样式 ---
C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYA=$'\033[36m'; C_RST=$'\033[0m'
step() { echo; echo "${C_CYA}▶ $*${C_RST}"; }
ok()   { echo "  ${C_GRN}✓${C_RST} $*"; }
warn() { echo "  ${C_YEL}!${C_RST} $*"; }
die()  { echo "${C_RED}✗ $*${C_RST}" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TOOLCHAIN="${PROJECT_DIR}/.toolchain"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${TOOLCHAIN}/android-sdk}}"
DL_DIR="${TOOLCHAIN}/downloads"

cd "$PROJECT_DIR"
mkdir -p "$DL_DIR" "$SDK_DIR"

# 判断宿主平台（决定用哪套 cmdline-tools 与 Gradle 发行包）
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST="windows" ;;
    Darwin)               HOST="mac" ;;
    Linux)                HOST="linux" ;;
    *) die "无法识别的主机系统：$(uname -s)" ;;
esac

if [[ "$HOST" != "windows" ]]; then
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-${HOST}-11076708_latest.zip"
fi

# =============================================================================
#  1. JDK
# =============================================================================
step "检查 JDK"

# 把 MSYS 风格路径（/d/foo）转成 Windows 风格（D:\foo）。
# Gradle 的启动脚本会用 `test -x "$JAVA_HOME/bin/java"` 校验，
# MSYS 风格路径在这个检查下会失败，所以 Windows 上必须转。
to_native_path() {
    if [[ "$HOST" == "windows" ]]; then
        if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
    else
        echo "$1"
    fi
}

find_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        echo "${JAVA_HOME}/bin/java"; return
    fi
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java.exe" ]]; then
        echo "${JAVA_HOME}/bin/java.exe"; return
    fi
    command -v java || true
}

JAVA_BIN="$(find_java)"
[[ -n "$JAVA_BIN" ]] || die "找不到 java。请安装 JDK 17 及以上，或设置 JAVA_HOME。"

JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "${JAVA_VER:-0}" -ge 17 ]] || die "JDK 版本过低（检测到 $JAVA_VER，需要 17+）"
ok "使用 JDK $JAVA_VER ($JAVA_BIN)"

# 统一成 Windows 风格路径，Gradle 启动脚本才认
if [[ "$HOST" == "windows" ]]; then
    if [[ -n "${JAVA_HOME:-}" ]]; then
        JAVA_HOME="$(to_native_path "$JAVA_HOME")"
    else
        JAVA_HOME="$(to_native_path "$(dirname "$(dirname "$JAVA_BIN")")")"
    fi
else
    [[ -n "${JAVA_HOME:-}" ]] || JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
fi
export JAVA_HOME
ok "JAVA_HOME=$JAVA_HOME"

# =============================================================================
#  2. Android SDK
# =============================================================================
step "准备 Android SDK"

SDKMANAGER_BAT="${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager.bat"
SDKMANAGER_CLI_CLASSPATH="${SDK_DIR}/cmdline-tools/latest/lib/sdkmanager-classpath.jar"

# 统一入口：优先用 .bat/.sh，失败则直接用 java 调主类
# （某些受限环境不允许执行 .bat，直调 java 更稳）
run_sdkmanager() {
    if [[ "$HOST" == "windows" ]]; then
        "$JAVA_BIN" \
            -Dcom.android.sdklib.toolsdir="$(to_native_path "${SDK_DIR}/cmdline-tools/latest/lib/..")" \
            -classpath "$(to_native_path "$SDKMANAGER_CLI_CLASSPATH")" \
            com.android.sdklib.tool.sdkmanager.SdkManagerCli \
            --sdk_root="$(to_native_path "$SDK_DIR")" "$@"
    else
        "${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" "$@"
    fi
}

if [[ ! -f "$SDKMANAGER_CLI_CLASSPATH" && ! -x "${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    ZIP="${DL_DIR}/cmdline-tools.zip"
    if [[ ! -f "$ZIP" ]]; then
        echo "  下载 cmdline-tools ..."
        curl -fL --retry 3 --connect-timeout 20 -o "$ZIP" "$CMDLINE_TOOLS_URL" \
            || die "cmdline-tools 下载失败，请检查网络"
    fi
    echo "  解压 cmdline-tools ..."
    rm -rf "${SDK_DIR}/cmdline-tools-tmp"
    unzip -q "$ZIP" -d "${SDK_DIR}/cmdline-tools-tmp"
    mkdir -p "${SDK_DIR}/cmdline-tools"
    rm -rf "${SDK_DIR}/cmdline-tools/latest"
    mv "${SDK_DIR}/cmdline-tools-tmp/cmdline-tools" "${SDK_DIR}/cmdline-tools/latest"
    rmdir "${SDK_DIR}/cmdline-tools-tmp"
    ok "cmdline-tools 就绪"
else
    ok "cmdline-tools 已存在"
fi

if [[ ! -f "${SDK_DIR}/platforms/${PLATFORM}/android.jar" || ! -d "${SDK_DIR}/build-tools/${BUILD_TOOLS}" ]]; then
    echo "  接受许可协议 ..."
    { for _ in $(seq 1 300); do echo y; done; } | run_sdkmanager --licenses >/dev/null 2>&1 || true

    echo "  安装 platform-tools / platforms;${PLATFORM} / build-tools;${BUILD_TOOLS} ..."
    echo "  （首次约 150MB，请耐心等待）"
    run_sdkmanager "platform-tools" "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" \
        | grep -viE '^\s*$' | tail -3
    ok "SDK 组件安装完成"
else
    ok "SDK 组件已存在（${PLATFORM} / build-tools ${BUILD_TOOLS}）"
fi

# AGP 通过 local.properties 的 sdk.dir 找 SDK。
# 注意：Java properties 文件里反斜杠是转义符（\U 会被吃掉），
# 所以这里统一写成正斜杠 —— Windows 下 Gradle 完全接受 C:/foo/bar 这种形式。
SDK_FOR_PROPS="$(to_native_path "$SDK_DIR")"
SDK_FOR_PROPS="${SDK_FOR_PROPS//\\//}"
{
    echo "sdk.dir=$SDK_FOR_PROPS"
    if [[ "$BACKEND" == "native" ]]; then
        echo "ffmpegx.backend=native"
    fi
} > local.properties
ok "已写入 local.properties (sdk.dir=$SDK_FOR_PROPS)"

# =============================================================================
#  3. Gradle
# =============================================================================
step "准备 Gradle"

GRADLE_HOME="${TOOLCHAIN}/gradle/gradle-${GRADLE_VERSION}"
if [[ "$HOST" == "windows" ]]; then
    GRADLE_BIN="${GRADLE_HOME}/bin/gradle.bat"
else
    GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
fi

if [[ ! -f "$GRADLE_BIN" ]]; then
    ZIP="${DL_DIR}/gradle-${GRADLE_VERSION}.zip"
    if [[ ! -f "$ZIP" ]]; then
        echo "  下载 Gradle ${GRADLE_VERSION} ..."
        curl -fL --retry 3 --connect-timeout 20 -o "$ZIP" \
            "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
            || die "Gradle 下载失败，请检查网络"
    fi
    echo "  解压 Gradle ..."
    mkdir -p "${TOOLCHAIN}/gradle"
    unzip -q "$ZIP" -d "${TOOLCHAIN}/gradle"
    ok "Gradle 就绪"
else
    ok "Gradle 已存在"
fi

# 生成 Wrapper，之后可以直接用 ./gradlew
if [[ ! -f "gradlew" ]]; then
    echo "  生成 Gradle Wrapper ..."
    "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon -q 2>/dev/null || \
        warn "Wrapper 生成失败（不影响本次构建）"
fi

# =============================================================================
#  4. 单元测试（可选）
# =============================================================================
if [[ $RUN_TESTS -eq 1 ]]; then
    step "运行单元测试"
    "$GRADLE_BIN" --no-daemon --console=plain \
        -Pffmpegx.backend="$BACKEND" \
        -Pffmpegx.abis="$ABIS" \
        ":app:testDebugUnitTest" \
        2>&1 | grep -vE '^\s*$' | tail -30
    ok "单元测试通过"
fi

# =============================================================================
#  5. 构建
# =============================================================================
step "构建 ${BUILD_TYPE} APK"

TASK="assemble$(echo "${BUILD_TYPE:0:1}" | tr '[:lower:]' '[:upper:]')${BUILD_TYPE:1}"
echo "  任务: :app:${TASK}"
echo "  后端: ${BACKEND}"
echo "  ABI : ${ABIS}"
echo

"$GRADLE_BIN" --no-daemon \
    -Pffmpegx.backend="$BACKEND" \
    -Pffmpegx.abis="$ABIS" \
    ":app:${TASK}" \
    2>&1 | grep -vE '^\s*$' | tail -60

# =============================================================================
#  6. 收集产物
# =============================================================================
step "收集产物"

DIST="${PROJECT_DIR}/dist"
mkdir -p "$DIST"

# 只清理本脚本自己产出的 app-*.apk，不动用户放进 dist 的其它文件
rm -f "$DIST"/app-*.apk

shopt -s nullglob
FOUND=0
for apk in app/build/outputs/apk/*/*.apk; do
    cp -f "$apk" "$DIST/"
    FOUND=$((FOUND + 1))
done
shopt -u nullglob

if [[ $FOUND -eq 0 ]]; then
    die "没有找到 APK，构建可能失败了。请向上翻看 Gradle 的输出。"
fi

echo
for apk in "$DIST"/*.apk; do
    SIZE="$(du -h "$apk" | cut -f1)"
    ok "$(basename "$apk")  (${SIZE})"
done

echo
echo "${C_GRN}构建完成 🎉${C_RST}"
echo
echo "  产物目录: ${DIST}"
echo "  ABI     : ${ABIS}"
echo
echo "  安装到手机："
echo "    adb install -r ${DIST}/app-${BUILD_TYPE}.apk"
echo "  或者直接把 APK 拷到手机点击安装。"
echo
