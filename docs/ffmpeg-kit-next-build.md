# 构建 ffmpeg-kit-next 的 Android AAR

FFmpegX 的 FFmpeg 核心是 [`arthenica/ffmpeg-kit-next`](https://github.com/arthenica/ffmpeg-kit-next)。
该库不发布 Maven Central / CocoaPods / npm 制品，只提供源码，因此必须先构建出 AAR，
FFmpegX 才能编译。

## 1. 环境要求

| 工作流 | 要求 |
|---|---|
| Nix（推荐） | 安装 [Nix](https://nixos.org/download)。profile `android-r27d` 自带 Android SDK、NDK r27d、CMake 与构建工具。NixOS 不能作为构建主机。 |
| 非 Nix | 自行准备 Android SDK、NDK r27d、CMake 与平台构建工具，并设置 `ANDROID_SDK_ROOT` / `ANDROID_NDK_ROOT`。 |

必须在 **Linux / macOS / WSL2** 上构建。Windows 原生环境只提供产出 Windows 目标的
`windows.sh`，无法构建 Android AAR。

## 2. 构建

```bash
# 1. 克隆 ffmpeg-kit-next 到 FFmpegX 的兄弟目录
git clone https://github.com/arthenica/ffmpeg-kit-next.git ../ffmpeg-kit-next

# 2. 构建（默认 minSdk 24、arm64-v8a + armeabi-v7a + x86_64、启用全部必需外部库）
./scripts/build-ffmpeg-kit-next.sh
```

脚本完成以下工作：

- 校验源码目录与宿主环境
- 将 FFmpegX 的 ABI 列表转换为上游的 `--disable-arch-*` 参数，裁掉用不到的架构
- 从 `scripts/ffmpeg-kit-next-libs.txt` 读取外部库清单并生成 `--enable-*` 开关
- 调用 `nix-android.sh -p android-r27d`
- 校验产物并提示需要同步的配置项

常用变体：

```bash
./scripts/build-ffmpeg-kit-next.sh --abis arm64-v8a   # 只出 arm64-v8a，构建时间与体积大幅降低
./scripts/build-ffmpeg-kit-next.sh --with openh264    # 在清单之外追加库
./scripts/build-ffmpeg-kit-next.sh --no-nix           # 不使用 Nix，依赖环境变量
```

完整选项见 `./scripts/build-ffmpeg-kit-next.sh --help`。

## 3. 手工构建

不使用封装脚本时：

```bash
cd ffmpeg-kit-next
./nix-android.sh --list-profiles

./nix-android.sh -p android-r27d \
  --api-level=24 \
  --disable-arch-arm-v7a-neon \
  --disable-arch-x86 \
  --enable-gpl \
  --enable-lib-x264 \
  --enable-lib-x265 \
  --enable-lib-android-media-codec \
  --enable-lib-lame \
  --enable-lib-opus \
  --enable-lib-libvorbis \
  --enable-lib-libvpx \
  --enable-lib-libass
```

产物结构：

```
ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven/
└── com/arthenica/ffmpeg-kit-next/9.0.0/
    ├── ffmpeg-kit-next-9.0.0.aar
    └── ffmpeg-kit-next-9.0.0.pom
```

## 4. 外部库清单

清单的唯一事实来源是 [`scripts/ffmpeg-kit-next-libs.txt`](../scripts/ffmpeg-kit-next-libs.txt)，
本地构建脚本与 CI 均从该文件读取。

**缺少任何一项都会导致对应功能在真机上失败，且构建过程不会给出任何提示** ——
ffmpeg-kit-next 对无法识别的 `--enable-*` 选项静默忽略，构建照常成功。

| 参数 | 缺失后果 |
|---|---|
| `--enable-gpl` | 不含 libx264 / libx265，「兼容优先」（全软编码）策略失效 |
| `--enable-lib-x264` / `--enable-lib-x265` | 同上，H.264 / HEVC 无法软件编码 |
| `--enable-lib-android-media-codec` | **MediaCodec 硬编硬解全部不可用。** `h264_mediacodec` 等编码器不存在于 libavcodec 中，而应用会依据设备 MediaCodec 能力生成 `-c:v h264_mediacodec`，导致每个转码任务立即失败：`Error opening output files: Encoder not found` |
| `--enable-lib-lame` | MP3 导出失败（缺 `libmp3lame`） |
| `--enable-lib-opus` | Opus 导出失败（缺 `libopus`） |
| `--enable-lib-libvorbis` | Vorbis 导出失败（缺 `libvorbis`） |
| `--enable-lib-libvpx` | VP8 / VP9 导出失败（缺 `libvpx`） |
| `--enable-lib-libass` | 字幕烧录失败（缺 `subtitles` 滤镜） |

> `--enable-lib-android-media-codec` 在 ffmpeg-kit-next 中默认关闭。这是最容易遗漏、
> 后果最严重的一项：遗漏后应用的全部硬件加速能力失效，而构建日志不会有任何异常。

命名规律：选项统一为 `--enable-lib-<库名>`，库名本身以 `lib` 开头时会出现双 `lib`
（`--enable-lib-libvorbis`、`--enable-lib-libvpx`、`--enable-lib-libass`）。
不存在 `--enable-x264` 这种写法。

许可证影响：启用 `--enable-gpl` 后整个 bundle 受 GPL-3.0 约束。FFmpegX 本身即为
GPL-3.0，因此无需改动 LICENSE。

CI 会在构建后拆开产物，逐一核对编码器与滤镜是否真的存在（见 `Verify AAR` 步骤），
避免开关被静默忽略而无人察觉。

## 5. 接入 FFmpegX

### 方式一：默认相对路径

将 ffmpeg-kit-next 克隆到 FFmpegX 的兄弟目录即可，无需配置 —— `settings.gradle.kts`
的默认值为 `../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven`。

### 方式二：显式指定

在 `gradle.properties`（或 `local.properties`，后者不进版本库）中：

```properties
ffmpegx.ffmpegKitNextRepo=/abs/path/to/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven
```

CI 中使用环境变量 `FFMPEGX_FFMPEG_KIT_NEXT_REPO`。

### 对齐版本号

`gradle/libs.versions.toml` 中的版本必须与构建产物一致：

```toml
ffmpegKitNext = "9.0.0"
```

版本号规则：前两位对应上游 FFmpeg 版本（`9.0.0` 使用 FFmpeg `9.0.1`），
第三位是 ffmpeg-kit-next 自身的修订号。

## 6. 常见问题

**构建报 `Invalid configuration detected. GPL library x264 enabled without --enable-gpl flag`**

缺少 `--enable-gpl`。脚本默认已带该参数，仅手工构建时可能遗漏。

**Gradle 报 `Could not find com.arthenica:ffmpeg-kit-next:9.0.0`**

本地 Maven 仓库路径不正确，或 AAR 尚未构建。检查 `settings.gradle.kts` 打印的仓库路径
是否存在，以及该目录下是否有 `com/arthenica/ffmpeg-kit-next/<version>/`。

**运行时 `FFmpegKit failed to start`**

通常是 ABI 不匹配：AAR 中不含当前设备的 ABI。用 `--abis` 确保覆盖设备架构，
或直接检查 APK 内的 `lib/<abi>/`。

**每个任务都立即失败，报 `Error opening output files: Encoder not found`**

AAR 缺少编码器，最常见的原因是遗漏 `--enable-lib-android-media-codec`。
此类问题在构建期不会报错，只能从产物中排查：解压 APK 取出 `lib/<abi>/libavcodec.so`，
在其中搜索编码器名（如 `h264_mediacodec`、`libx264`）。CI 的 `Verify AAR` 步骤已内置
这道校验。

**APK 体积过大**

构建时用 `--abis` 裁掉不用的架构，并同步调整 `gradle.properties` 中的 `ffmpegx.abis`，
两边保持一致，否则会装入用不到的 `.so`。
