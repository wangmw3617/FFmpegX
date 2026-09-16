# FFmpegX

![Build](https://github.com/wangmw3617/FFmpegX/actions/workflows/build.yml/badge.svg)
![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)
![minSdk 24](https://img.shields.io/badge/minSdk-24-green.svg)
![targetSdk 36](https://img.shields.io/badge/targetSdk-36-green.svg)

FFmpegX 是一个面向 Android 平台的 FFmpeg 图形前端。它将 FFmpeg 的命令行能力封装为移动端界面，并在运行时依据设备实际支持的 MediaCodec 编解码能力，自动选择硬件加速方案。

- 技术栈：Kotlin + Jetpack Compose（Material 3）
- 版本要求：最低 Android 7.0（API 24），目标 Android 16（API 36）

## 构建

```bash
./scripts/build-apk.sh
```

该脚本会自动完成 Android SDK 与 Gradle 的下载安装，并将生成的 APK 输出至 `dist/` 目录。全部工具链位于项目的 `.toolchain/` 目录下，删除后可重新初始化。Windows 环境下可直接运行 `build-apk.cmd`。

构建亦可交由 GitHub Actions 完成：推送代码或创建 `v*` 标签会触发自动测试与打包流程，创建标签时还会自动发布 Release（按 CPU 架构分别提供 APK）。本地构建需要 JDK 17 或更高版本，其余依赖均由脚本自动安装。

## 硬件加速

本项目不依据 SoC 型号推断能力，而是在运行时枚举设备上的全部 MediaCodec 编解码器，读取其支持的分辨率、帧率与码率范围，据此决定解码与编码方案。

方案规划遵循以下规则：

1. 使用零拷贝（`-hwaccel_output_format mediacodec`）时，输出侧不得包含任何 CPU 滤镜，且编码器必须为 MediaCodec，否则会触发格式转换错误。
2. 仅指定 `-hwaccel mediacodec`（不指定 output_format）时，FFmpeg 会将帧回传至系统内存，此时 CPU 滤镜可正常使用，兼容性最佳。
3. 硬件编码器对分辨率与帧率存在上限，超出范围时回退至软件编码。

界面提供四种策略：智能、速度优先、质量优先、兼容优先。其中「质量优先」采用硬件解码配合软件编码。

## FFmpeg 集成方式

上层代码不区分后端，因为两种方式执行的是同一条 ffmpeg 命令，区别仅在于执行者。

- **kit**（默认）：使用 Maven 上预编译的 ffmpeg-kit（FFmpeg 8.1.1 Full），无需 NDK，可在任意平台构建。
- **native**：使用 `app/src/main/cpp` 下的自研 JNI 层，需先在 Linux / macOS / WSL 环境运行 `scripts/build-ffmpeg-android.sh` 编译 FFmpeg。

切换方式：修改 `gradle.properties` 中的 `ffmpegx.backend`，或通过命令行参数 `-Pffmpegx.backend=native` 指定。

官方 ffmpeg-kit 已于 2025 年初从 Maven Central 下架，本项目使用社区维护的分支 `com.antonkarpenko:ffmpeg-kit-full`。

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
- 部分文件在选择时会复制至应用缓存。SAF 提供的管道不支持 seek，而 FFmpeg 需要可寻址输入。
- 输出不支持直接写入 SAF，需通过「导出到媒体库」完成。
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
  core/media/    Uri 到真实路径的解析
  core/task/     Room 任务队列与前台服务
  ui/            Compose 界面
app/src/kit/java/      ffmpeg-kit 后端
app/src/native/java/   自研 JNI 后端
scripts/               构建脚本
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

本项目采用 GPL-3.0 许可证。默认后端使用的 ffmpeg-kit-full 包含 libx264 / libx265 等 GPL 组件，依据 FFmpeg 的许可要求，分发链接这些组件的应用须整体以 GPL 兼容许可证发布。若仅使用 LGPL 组件自行编译，可另行选择许可证。
