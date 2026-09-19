# FFmpegX

![Build](https://github.com/wangmw3617/FFmpegX/actions/workflows/build.yml/badge.svg)
![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)
![minSdk 24](https://img.shields.io/badge/minSdk-24-green.svg)
![targetSdk 36](https://img.shields.io/badge/targetSdk-36-green.svg)

FFmpegX 是面向 Android 平台的 FFmpeg 图形前端。它将 FFmpeg 的命令行能力封装为移动端界面，
并在运行时枚举设备实际支持的 MediaCodec 编解码能力，据此自动选择硬件加速方案。

| | |
|---|---|
| 界面 | Kotlin + Jetpack Compose（Material 3） |
| FFmpeg 核心 | [ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next)（FFmpeg 9.x） |
| 系统要求 | Android 7.0（API 24）及以上，目标 Android 16（API 36） |
| 许可证 | GPL-3.0 |

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
| 水印与画中画 | 图片水印、画中画、分屏 |
| 画面变换 | 旋转翻转、画面裁剪、提取画面 |
| 进阶处理 | 视频变速、去水印、图片转视频 |
| 媒体信息 | ffprobe 全字段解析 |
| 命令行 | 直接输入 ffmpeg 参数 |
| 任务队列 | 串行执行、进度、取消、前台服务 |

首页展示当前设备实际支持的硬件编解码能力。

## 硬件加速

不依据 SoC 型号推断能力，而是在运行时枚举设备上全部 MediaCodec 编解码器，读取其支持的
分辨率、帧率与码率范围，据此决定解码与编码方案。规划遵循三条规则：

1. 使用零拷贝（`-hwaccel_output_format mediacodec`）时，输出侧不得包含任何 CPU 滤镜，
   且编码器必须为 MediaCodec。
2. 仅指定 `-hwaccel mediacodec`（不指定 output_format）时，FFmpeg 会将帧回传至系统内存，
   CPU 滤镜可正常使用，兼容性最佳。
3. 硬件编码器对分辨率与帧率存在上限，超出范围时回退至软件编码。

界面提供四种策略：智能、速度优先、质量优先、兼容优先。

## 环境要求

| 用途 | 要求 |
|---|---|
| 构建 APK | JDK 17+、Android SDK（platform 36 / build-tools 36.0.0） |
| 构建 AAR | Linux / macOS / WSL2，并安装 [Nix](https://nixos.org/download) |

构建 AAR 无法在 Windows 原生环境完成 —— 上游只提供产出 Windows 目标的 `windows.sh`。

## 构建

### 1. 构建 ffmpeg-kit-next AAR

ffmpeg-kit-next 只发布源码、不发布二进制制品，因此首次构建前必须先自行编译出 AAR：

```bash
git clone https://github.com/arthenica/ffmpeg-kit-next.git ../ffmpeg-kit-next
./scripts/build-ffmpeg-kit-next.sh
```

脚本默认产出 minSdk 24、`arm64-v8a` + `armeabi-v7a` + `x86_64` 三个架构的 AAR，
并启用应用真实依赖的全部外部库。产物落在 ffmpeg-kit-next 的 `prebuilt/` 下，
FFmpegX 会自动从默认相对路径解析，也可用 `ffmpegx.ffmpegKitNextRepo` 指定。

外部库清单见 `scripts/ffmpeg-kit-next-libs.txt`，本地脚本与 CI 均从该文件读取。
详细说明与常见问题见 [docs/ffmpeg-kit-next-build.md](docs/ffmpeg-kit-next-build.md)。

### 2. 构建 APK

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（未配置签名时产出未签名包）
```

按 ABI 拆包，每个 CPU 架构单独产出一个 APK。要打包的架构由 `gradle.properties`
的 `ffmpegx.abis` 控制，需与构建 AAR 时使用的架构保持一致，否则会装入用不到的 `.so`。

## 测试与检查

```bash
./gradlew :app:testDebugUnitTest                                # 单元测试
python3 scripts/checks/ktscan.py $(git ls-files '*.kt')         # 括号与字符串闭合
python3 scripts/checks/dupcheck.py $(git ls-files '*.kt')       # 类体重复成员
python3 scripts/checks/composecheck.py $(git ls-files '*.kt')   # @Composable 上下文
python3 scripts/checks/importcheck.py app/src                   # 导入符号存在性
```

四个检查器均为纯词法/结构分析，秒级完成，不需要 AAR 与 Android SDK。

## 持续集成

工作流见 [.github/workflows/build.yml](.github/workflows/build.yml)。

| 事件 | 行为 |
|---|---|
| push 到 `main` | 静态检查 → 构建 AAR → 单元测试与打包并行 |
| PR 到 `main` | 同上 |
| push tag `v*` | 在上述基础上产出签名 APK 并创建 Release |
| 手动触发 | 用于重跑 |

仅改动文档（`**.md`、`docs/**` 等）时跳过整套流水线。同一分支连续推送时只保留最后一次运行。

AAR 构建耗时较长，因此按「版本号 + ABI + 外部库清单指纹」缓存；修改库清单会自动使缓存
失效并触发重建，这是预期行为。

## 发布

打 tag 即完成发版，无需修改代码：

```bash
git tag v1.0.3
git push origin v1.0.3
```

版本号从 tag 名取得（`v1.0.3` → `1.0.3`），`versionCode` 由其推导并保证单调递增。
Release 附件按 CPU 架构分别提供已签名的 APK。

发布签名通过仓库 Secrets 注入：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、
`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。未配置时 release 包保持未签名，
本地与 fork 仍可正常构建。

## 输出位置

转码完成后结果自动导出到系统下载目录，无需存储权限（Android 10+ 使用 Scoped Storage）：

```
Download/FFmpegX/
```

应用私有目录同时保留一份副本，供应用内预览与重试。卸载应用时私有目录会被系统清理，
`Download/FFmpegX` 下的文件保留。输出目录可在设置中指定。

## 已知限制

- 任务串行执行。FFmpeg 命令行存在大量进程级全局状态，串行是正确性要求。
- 「视频拼接」的**通用重编码模式要求每一段都含音频轨**。该模式走 concat 滤镜，
  会为每段显式取 `[i:a:0]`，任一段没有音频轨就会整体失败（报
  `Stream specifier ':a:0' ... matches no streams`）。用「快速合并」（concat demuxer）
  不受影响。要支持混合素材，需先逐段探测音频轨是否存在。
- 字幕烧录要求字幕是**真实文件**：`subtitles` 滤镜把路径交给 libass，而 libass
  自己 `fopen` 读文件、不走 FFmpeg 的 avio 协议层，因此不认 `ffkitsaf:`。
  从系统选择器选字幕后会先复制一份到应用缓存（字幕通常只有几十 KB，代价可忽略）。
- 「媒体信息」页直接读取 SAF 文档时会临时复制一份副本供 ffprobe 使用（ffprobe 需要
  可寻址输入），探测完成后立即删除；转码主流程不受影响，仍走零拷贝直读。
- 转码流程尚未在真机或模拟器上完成实机验证，目前仅由单元测试覆盖。

## 目录结构

```
app/src/main/java/com/zhiwei/ffmpegx/
  native/        ffmpeg-kit-next 后端封装
  core/hw/       硬件加速规划（MediaCodec 枚举与方案计算）
  core/cmd/      命令构造
  core/engine/   执行会话与进度解析
  core/media/    SAF Uri 解析与媒体库导出
  core/task/     Room 任务队列与前台服务
  ui/            Compose 界面
scripts/
  build-ffmpeg-kit-next.sh     构建 AAR
  ffmpeg-kit-next-libs.txt     外部库清单（唯一事实来源）
  checks/                      静态检查器
docs/                          构建文档
.github/                       CI 工作流与复合 action
```

## 许可证

本项目采用 GPL-3.0 许可证。

ffmpeg-kit-next 自身为 LGPL-3.0，但本项目必须启用 GPL 库构建
（`--enable-gpl --enable-lib-x264 --enable-lib-x265`），否则不含 libx264 / libx265，
「兼容优先」（全软编码）策略会直接失效。启用后整个 bundle 受 GPL-3.0 约束，
与本项目许可证一致。
