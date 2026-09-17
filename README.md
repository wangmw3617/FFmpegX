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

ffmpeg-kit-next 只发源码、不发布 Maven 制品，因此首次构建前必须先自行编译出 AAR：

```bash
git clone https://github.com/arthenica/ffmpeg-kit-next.git ../ffmpeg-kit-next
./scripts/build-ffmpeg-kit-next.sh
```

脚本需要 **Linux / macOS / WSL2**（上游未提供可产出 Android 目标的 Windows 构建），默认产出 minSdk 24、`arm64-v8a` + `armeabi-v7a` + `x86_64` 三个架构、并启用 GPL 库的 AAR。产物落在 ffmpeg-kit-next 的 `prebuilt/` 下，FFmpegX 会自动从默认相对路径解析，也可用 `ffmpegx.ffmpegKitNextRepo` 指定。详见 [docs/ffmpeg-kit-next-build.md](docs/ffmpeg-kit-next-build.md)。

之后正常构建即可（需要 JDK 17+）：

```bash
./scripts/build-apk.sh     # 自动安装 Android SDK / Gradle，产物落在 dist/
```

Windows 上可双击 `build-apk.cmd`。构建同样可以完全交给 GitHub Actions：推送代码或创建 `v*` 标签会触发「构建 AAR → 单元测试 → 打包」流水线，创建标签时还会发布 Release（按 CPU 架构分别提供已签名的 APK）。

## 硬件加速

不依据 SoC 型号推断能力，而是在运行时枚举设备上的全部 MediaCodec 编解码器，读取其支持的分辨率、帧率与码率范围，据此决定解码与编码方案。规划遵循三条规则：

1. 使用零拷贝（`-hwaccel_output_format mediacodec`）时，输出侧不得包含任何 CPU 滤镜，且编码器必须为 MediaCodec。
2. 仅指定 `-hwaccel mediacodec`（不指定 output_format）时，FFmpeg 会将帧回传至系统内存，CPU 滤镜可正常使用，兼容性最佳。
3. 硬件编码器对分辨率与帧率存在上限，超出范围时回退至软件编码。

界面提供四种策略：智能、速度优先、质量优先、兼容优先。

## FFmpeg 集成

核心为 **ffmpeg-kit-next**（原 ffmpeg-kit 作者 Taner Sener 的官方续作）。它执行的就是原样的 ffmpeg 命令行，上层无需感知底层实现，并额外提供结构化进度回调与 `FFprobeKit.getMediaInformation()`；内建的 `ffkitsaf:` / `ffkitmem:` / `ffkitstream:` 协议可直接读写 SAF Uri，省去复制中转。迁移细节见 [docs/migration-to-kit-next.md](docs/migration-to-kit-next.md)。

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

## 输出位置

转码结果默认写入应用私有外部目录，不需要任何存储权限：

```
Android/data/com.zhiwei.ffmpegx/files/Movies/FFmpegX/
```

需要出现在相册 / 音乐 / 文件管理器里时，用结果卡片上的「导出到媒体库」，会复制一份到对应的系统目录（`Movies/FFmpegX`、`Music/FFmpegX` 等）。输出目录也可在设置中指定。

## 已知限制

- 任务串行执行。FFmpeg 命令行存在大量进程级全局状态，串行是正确性要求。
- 「媒体信息」页直接读取 SAF 文档时会临时复制一份副本供 ffprobe 使用（ffprobe 需要可寻址输入），探测完成后立即删除；转码主流程不受影响，仍走零拷贝直读。
- 转码流程尚未在真机或模拟器上完成实机验证，目前仅由单元测试覆盖。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

85 个单元测试，覆盖硬件加速规划、命令生成、进度解析与预设换算等易错逻辑。

## 目录结构

```
app/src/main/java/com/zhiwei/ffmpegx/
  core/hw/       硬件加速规划（MediaCodec 枚举与方案计算）
  core/cmd/      命令构造
  core/engine/   执行会话与进度解析
  core/media/    SAF Uri 解析与媒体库导出
  core/task/     Room 任务队列与前台服务
  native/        ffmpeg-kit-next 后端封装
  ui/            Compose 界面
scripts/         构建脚本
docs/            构建与迁移文档
```

## 发布签名

发布包使用标准的 Android 签名流程。密钥与口令不入库：本地放在根目录的 `keystore.properties`（已 gitignore），CI 从 GitHub Secrets 注入。

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias ffmpegx -keyalg RSA -keysize 4096 -validity 10000
```

本地构建时在根目录新建 `keystore.properties`：

```properties
storeFile=release.keystore
storePassword=你的口令
keyAlias=ffmpegx
keyPassword=你的口令
```

CI 则在仓库 Settings → Secrets and variables → Actions 中添加：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出 |
| `RELEASE_STORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

签名默认启用 APK Signature Scheme v1 / v2 / v3。

> 若上架 Google Play，建议改用 App Bundle（`bundleRelease`）并开启 Play App Signing：由 Play 持有应用签名密钥，本地仅保留上传密钥，遗失后可申请重置。

## 许可证

本项目采用 GPL-3.0 许可证。

ffmpeg-kit-next 自身为 LGPL-3.0，但本项目必须启用 GPL 库构建（`--enable-gpl --enable-lib-x264 --enable-lib-x265`），否则不含 libx264 / libx265，「兼容优先」（全软编码）策略会直接失效。启用后整个 bundle 受 GPL-3.0 约束，与本项目许可证一致。
