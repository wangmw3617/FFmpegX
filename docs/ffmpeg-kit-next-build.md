# 构建 ffmpeg-kit-next 的 Android AAR

FFmpegX 的唯一 FFmpeg 核心是 [`arthenica/ffmpeg-kit-next`](https://github.com/arthenica/ffmpeg-kit-next)。
它**不发布** Maven Central / CocoaPods / npm 制品，只发源码 ——
所以必须先自己构建出 AAR，FFmpegX 才能编译。

---

## 1. 宿主环境要求

| 工作流 | 要求 |
|---|---|
| **Nix（推荐）** | 安装 [Nix](https://nixos.org/download)。profile `android-r27d` 自带 Android SDK、NDK r27d、CMake 与构建工具。**NixOS 不能作为构建主机。** |
| **非 Nix** | 自行准备 Android SDK + **NDK r27d** + CMake + 平台构建工具，并设好 `ANDROID_SDK_ROOT` / `ANDROID_NDK_ROOT` |

**平台限制**：必须在 **Linux / macOS / WSL2** 上构建。
Windows 原生环境只提供 `windows.sh`（产出 Windows 目标），无法构建 Android AAR。

---

## 2. 一条命令（推荐）

FFmpegX 仓库里已经封装好了：

```bash
# 1. 克隆 ffmpeg-kit-next 到 FFmpegX 的兄弟目录
git clone https://github.com/arthenica/ffmpeg-kit-next.git ../ffmpeg-kit-next

# 2. 构建（默认 minSdk 24、arm64-v8a + armeabi-v7a + x86_64、启用 GPL 库）
./scripts/build-ffmpeg-kit-next.sh
```

脚本做的事：
- 校验源码目录与宿主环境
- 把 FFmpegX 的 ABI 列表翻译成上游的 `--disable-arch-*` 参数（裁掉用不到的架构）
- 启用 App 真实用到的**全部**外部库（少一个就会有功能报错，见第 4 节）
- 调用 `nix-android.sh -p android-r27d`
- 校验产物并打印后续要填的配置

常用变体：

```bash
# 只出 arm64-v8a，构建时间与产物体积都大幅降低
./scripts/build-ffmpeg-kit-next.sh --abis arm64-v8a

# 在默认清单之外追加库
./scripts/build-ffmpeg-kit-next.sh --with openh264

# 不用 Nix（需自行配好 SDK / NDK r27d 环境变量）
./scripts/build-ffmpeg-kit-next.sh --no-nix
```

---

## 3. 手工构建（不做封装时）

```bash
cd ffmpeg-kit-next

# 看有哪些 profile
./nix-android.sh --list-profiles

# 构建
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

产物：

```
ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven/
└── com/arthenica/ffmpeg-kit-next/9.0.0/
    ├── ffmpeg-kit-next-9.0.0.aar
    └── ffmpeg-kit-next-9.0.0.pom
```

---

## 4. 必须开启的库（漏一个就会有功能报错）

构建参数不是「能跑就行」。**少开一个库，对应功能在真机上会直接失败**，
而且构建过程**完全不会报错** —— ffmpeg-kit-next 对不认识的 `--enable-*` 选项
是静默忽略的，CI 照样绿。

| 参数 | 少了会怎样 |
|---|---|
| `--enable-gpl` | 没有 libx264/libx265，「兼容优先」（全软编）策略失效 |
| `--enable-lib-x264` / `--enable-lib-x265` | 同上，H.264 / HEVC 无法软件编码 |
| **`--enable-lib-android-media-codec`** | **MediaCodec 硬编硬解全部不可用。** `h264_mediacodec` 等编码器在 libavcodec 里根本不存在，而 App 会依据设备 MediaCodec 能力生成 `-c:v h264_mediacodec` → 每个转码任务都秒失败：`Error opening output files: Encoder not found` |
| `--enable-lib-lame` | MP3 导出失败（缺 `libmp3lame`） |
| `--enable-lib-opus` | Opus 导出失败（缺 `libopus`） |
| `--enable-lib-libvorbis` | Vorbis 导出失败（缺 `libvorbis`） |
| `--enable-lib-libvpx` | VP8 / VP9 导出失败（缺 `libvpx`） |
| `--enable-lib-libass` | 字幕烧录失败（缺 `subtitles` 滤镜） |

> ⚠️ **`--enable-lib-android-media-codec` 在 ffmpeg-kit-next 里默认是关闭的。**
> 这是最容易漏、后果最严重的一项：漏了它，App 的整个「硬件加速」卖点都形同虚设，
> 而构建日志里不会有任何异常。
>
> 另外，CI 的 AAR 缓存 key **必须包含库清单指纹** —— 只改参数不改 key 会命中旧
> AAR，新参数永远不生效。

命名规律：选项统一是 `--enable-lib-<库名>`，而库名本身带 `lib` 的会出现双 `lib`
（`--enable-lib-libvorbis`、`--enable-lib-libvpx`、`--enable-lib-libass`）。
不存在 `--enable-x264` 这种写法。

许可证影响：开了 `--enable-gpl` 后整个 bundle 受 **GPL-3.0** 约束。
FFmpegX 本身已经是 GPL-3.0，所以**不需要改动 LICENSE**。

---

## 5. 接到 FFmpegX

### 方式一：默认相对路径

把 `ffmpeg-kit-next` 克隆到 FFmpegX 的兄弟目录即可，无需配置 ——
`settings.gradle.kts` 的默认值就是 `../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven`。

### 方式二：显式指定

`gradle.properties`（或在 `local.properties` 里，后者不进版本库）：

```properties
ffmpegx.ffmpegKitNextRepo=/abs/path/to/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven
```

CI 里用环境变量：

```bash
export FFMPEGX_FFMPEG_KIT_NEXT_REPO=/path/to/repo
```

### 对齐版本号

`gradle/libs.versions.toml` 里的版本必须与构建产物的版本一致：

```toml
ffmpegKitNext = "9.0.0"
```

版本号规则：**前两位对应上游 FFmpeg 版本**（`9.0.0` 用的是 FFmpeg `9.0.1`），
第三位是 ffmpeg-kit-next 自己的修订号。

---

## 6. 常见问题

**构建报 `Invalid configuration detected. GPL library x264 enabled without --enable-gpl flag`**
→ 缺 `--enable-gpl`。脚本默认已带，若是手工构建漏了。

**Gradle 报 `Could not find com.arthenica:ffmpeg-kit-next:9.0.0`**
→ 本地 Maven 仓库路径不对，或 AAR 没构建出来。检查
`settings.gradle.kts` 打印的仓库路径是否存在，以及该目录下是否有
`com/arthenica/ffmpeg-kit-next/<version>/`。

**运行时 `FFmpegKit failed to start`**
→ 多半是 ABI 不匹配：AAR 里没有当前设备的 ABI。
用 `--abis` 确保包含了设备架构，或直接查 APK 里的 `lib/<abi>/`。

**APK 体积过大**
→ 构建时用 `--abis` 裁掉不用的架构，并同步调整 `gradle.properties` 里的
`ffmpegx.abis`，两边保持一致，否则会白装一份用不到的 `.so`。

**运行时每个任务都秒失败，报 `Error opening output files: Encoder not found`**
→ AAR 里缺编码器，最常见的是漏了 `--enable-lib-android-media-codec`（硬编硬解）。
这类问题**构建期完全不会报错**，只能从产物里查：解压 APK 取出
`lib/<abi>/libavcodec.so`，在里面搜编码器名（如 `h264_mediacodec`、`libx264`）。
CI 的 `Verify AAR` 步骤已经内置了这道硬校验。

**想减小体积又保留全部功能**
→ 构建时用 `--disable-lib-<name>` 关掉用不到的外部库，
比 `--enable-lib-all` 小得多。但**不要删第 4 节表格里的任何一项** ——
每一项都对应 App 里真实会生成的参数。
