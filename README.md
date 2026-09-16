# FFmpegX

![Build](https://github.com/wangmw3617/FFmpegX/actions/workflows/build.yml/badge.svg)
![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)
![minSdk 24](https://img.shields.io/badge/minSdk-24-green.svg)
![targetSdk 36](https://img.shields.io/badge/targetSdk-36-green.svg)

FFmpegX 是一个面向 Android 平台的 FFmpeg 图形前端。它将 FFmpeg 的命令行能力封装为移动端界面，并在运行时依据设备实际支持的 MediaCodec 编解码能力，自动选择硬件加速方案。

- 技术栈：Kotlin + Jetpack Compose（Material 3）
- FFmpeg 核心：ffmpeg-kit-next（arthenica 官方续作，FFmpeg 9.x）
- 版本要求：最低 Android 7.0（API 24），目标 Android 16（API 36）

## 构建

```bash
./scripts/build-apk.sh
```

该脚本会自动完成 Android SDK 与 Gradle 的下载安装，并将生成的 APK 输出至 `dist/` 目录。全部工具链位于项目的 `.toolchain/` 目录下，删除后可重新初始化。Windows 环境下可直接运行 `build-apk.cmd`。

构建亦可交由 GitHub Actions 完成：推送代码或创建 `v*` 标签会触发自动测试与打包流程，创建标签时还会自动发布 Release（按 CPU 架构分别提供 APK）。本地构建需要 JDK 17 或更高版本，其余依赖均由脚本自动安装。

### 先决条件：构建 FFmpeg 核心

FFmpegX 使用 ffmpeg-kit-next 作为 FFmpeg 核心。它**不发布** Maven Central 制品，只发源码，因此首次构建前必须先自行编译出 AAR：

```bash
# 需要 Linux / macOS / WSL2；Nix 工作流需先安装 Nix
git clone https://github.com/arthenica/ffmpeg-kit-next.git ../ffmpeg-kit-next
./scripts/build-ffmpeg-kit-next.sh
```

脚本默认产出 minSdk 24、`arm64-v8a` + `armeabi-v7a` + `x86_64` 三个架构、并启用 GPL 库的 AAR。产物落在 ffmpeg-kit-next 的 `prebuilt/` 下，FFmpegX 会自动从默认相对路径解析。详细选项与排错见 [docs/ffmpeg-kit-next-build.md](docs/ffmpeg-kit-next-build.md)。

> Windows 原生环境无法构建（上游只提供 Windows 目标的构建脚本），请使用 WSL2。

## 硬件加速

本项目不依据 SoC 型号推断能力，而是在运行时枚举设备上的全部 MediaCodec 编解码器，读取其支持的分辨率、帧率与码率范围，据此决定解码与编码方案。

方案规划遵循以下规则：

1. 使用零拷贝（`-hwaccel_output_format mediacodec`）时，输出侧不得包含任何 CPU 滤镜，且编码器必须为 MediaCodec，否则会触发格式转换错误。
2. 仅指定 `-hwaccel mediacodec`（不指定 output_format）时，FFmpeg 会将帧回传至系统内存，此时 CPU 滤镜可正常使用，兼容性最佳。
3. 硬件编码器对分辨率与帧率存在上限，超出范围时回退至软件编码。

界面提供四种策略：智能、速度优先、质量优先、兼容优先。其中「质量优先」采用硬件解码配合软件编码。

## FFmpeg 集成方式

FFmpeg 核心为 **ffmpeg-kit-next**，即原 ffmpeg-kit 作者 Taner Sener 的官方续作。执行的就是原样的 ffmpeg 命令行，因此上层的命令生成与硬件加速规划完全不需要感知底层实现。

选择它的原因：

- 官方持续维护（原 `com.arthenica` 制品于 2025 年初下架，社区分支 `com.antonkarpenko` 已不再需要）；
- 提供结构化的 `StatisticsCallback` 进度回调与 `FFprobeKit.getMediaInformation()`，无需从日志正则解析；
- 内建 `ffkitsaf:` / `ffkitmem:` / `ffkitstream:` 协议，可直接读写 SAF Uri，省掉「复制到缓存」的中转；
- 重写了执行入口，不存在裸 fftools 会因 `exit()` 终止宿主进程的问题。

代价是必须自行构建 AAR，见上文。历史上本项目还提供过「自研 JNI 编译 fftools」的第二后端，已随本次切换移除——既然都要自行构建，保留两条编译链只会让维护成本翻倍。迁移细节见 [docs/migration-to-kit-next.md](docs/migration-to-kit-next.md)。

## 功能

| 模块 | 说明 |
|---|---|
| 格式转换 | 容器、编码器、CRF / ABR、缩放、帧率、音频编码 |
| 视频压缩 | 8 种预设与体积预估 |
| 剪辑截取 | 无损剪切与精确重编码 |
| 音频处理 | 提取、变速、响度标准化 |
| GIF 制作 | 双遍调色板编码 |
| 视频拼接 | 无损拼接与通用重编码 |
| 字幕处理 | 烧录、提取、封装 |
| 水印 / 画中画 | 图片水印、画中画、分屏 |
| 媒体信息 | ffprobe 全字段解析 |
| 命令行 | 直接输入 ffmpeg 参数 |
| 任务队列 | 串行执行、进度、取消、前台服务 |

首页展示当前设备实际支持的硬件编解码能力。

## 已知限制

- 任务串行执行。FFmpeg 命令行存在大量进程级全局状态，串行是正确性要求。
- 「媒体信息」页在直接读取 SAF 文档时会临时复制一份副本供 ffprobe 使用（ffprobe 需要可寻址输入），探测完成后立即删除。转码主流程不受影响，仍走零拷贝直读。
- 输出到设备上任意位置需通过「导出到媒体库」完成。
- release 包未配置签名，发布前需自行配置。
- 转码流程尚未在真机或模拟器上完成实机验证，目前仅由单元测试覆盖。

## 测试

项目包含 85 个单元测试，覆盖硬件加速规划、命令生成、进度解析与预设换算等易错逻辑：

```bash
./gradlew :app:testDebugUnitTest
```

测试过程中发现并修复了两处缺陷：其一，丢弃视频流时仍生成 `-vf`，与 `-vn` 冲突；其二，「质量优先」策略未在规划器中生效。

## 目录结构

```
app/src/main/java/com/zhiwei/ffmpegx/
  core/hw/       硬件加速规划（MediaCodec 枚举与方案计算）
  core/cmd/      命令构造
  core/engine/   执行会话与进度解析
  core/media/    SAF Uri 解析（优先直读，回退缓存中转）
  core/task/     Room 任务队列与前台服务
  native/        ffmpeg-kit-next 后端封装
  ui/            Compose 界面
scripts/               构建脚本
 docs/                  构建与迁移文档
```

## 发布签名

发布包使用标准的 Android 签名流程。密钥与口令不入库：本地放在根目录的 `keystore.properties`（已 gitignore），CI 从 GitHub Secrets 注入。

生成密钥库（一次性操作，请妥善备份）：

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias ffmpegx -keyalg RSA -keysize 4096 -validity 10000
```

本地构建：在根目录新建 `keystore.properties`：

```properties
storeFile=release.keystore
storePassword=你的口令
keyAlias=ffmpegx
keyPassword=你的口令
```

CI 构建：在仓库 Settings → Secrets and variables → Actions 添加以下 Secret：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出 |
| `RELEASE_STORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名（如 `ffmpegx`） |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

配置后，CI 会产出签名的 release 包并按架构发布；未配置时自动退回 debug 包。签名默认启用 APK Signature Scheme v1 / v2 / v3。

> 若计划上架 Google Play，建议改用 App Bundle（`bundleRelease`）并开启 Play App Signing：由 Play 持有应用签名密钥，本地仅保留上传密钥，遗失后可申请重置。

## 许可证

本项目采用 GPL-3.0 许可证。

ffmpeg-kit-next 自身为 LGPL-3.0，但本项目的构建**必须**启用 GPL 库（`--enable-gpl --enable-lib-x264 --enable-lib-x265`），否则不含 libx264 / libx265，「兼容优先」（全软编码）策略会直接失效。启用后整个 bundle 受 GPL-3.0 约束，与本项目许可证一致。

若仅使用 LGPL 组件自行编译，可另行选择许可证，但需同时放弃软件编码回退能力。
