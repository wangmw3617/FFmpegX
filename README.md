# FFmpegX

![Build](https://github.com/wangmw3617/FFmpegX/actions/workflows/build.yml/badge.svg)
![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)
![minSdk 24](https://img.shields.io/badge/minSdk-24-green.svg)
![targetSdk 36](https://img.shields.io/badge/targetSdk-36-green.svg)

手机上用的 FFmpeg 图形前端。把 ffmpeg 那套命令行包成能点的界面，顺便在运行时挑出设备真正支持的硬件编解码。

Kotlin + Jetpack Compose，最低 Android 7.0，目标 Android 16。

## 编译

```bash
./scripts/build-apk.sh
```

脚本会自己下 Android SDK 和 Gradle，跑完把 APK 放到 `dist/`。工具链都在 `.toolchain/` 下，想重来删掉就行。Windows 上双击 `build-apk.cmd` 也一样。

也可以交给 GitHub Actions：推代码或打 `v*` 标签就自动跑测试、出包；打标签还会顺手发 Release（每个 CPU 架构一个 APK）。需要 JDK 17+，其余都自动装。

## 硬件加速

不靠查表猜芯片，直接问系统：把设备上的 MediaCodec 全枚举一遍，看哪些能硬解硬编、能顶到多大分辨率和帧率。

规划方案时有几条死规矩，都是踩过坑总结出来的：

1. 想零拷贝（`-hwaccel_output_format mediacodec`），后面就不能挂任何 CPU 滤镜，编码器也得是 MediaCodec，否则一定报格式转换失败。
2. 只写 `-hwaccel mediacodec`、不写 output_format 最稳，FFmpeg 会把帧拉回内存，滤镜随便用。
3. 硬编有分辨率和帧率上限，超了就老老实实回退软编。

界面给了四种策略：智能、速度优先、质量优先、兼容优先。质量优先 = 硬解 + 软编。

## 两种 FFmpeg 接法

上层代码不关心用哪种，因为跑的是同一条 ffmpeg 命令，差别只在谁来执行。

- **kit**（默认）：用 Maven 上的预编译 ffmpeg-kit（FFmpeg 8.1.1 Full）。不用 NDK，任何机器都能编。
- **native**：用 `app/src/main/cpp` 里的自研 JNI。得先在 Linux / macOS / WSL 上跑 `scripts/build-ffmpeg-android.sh` 把 FFmpeg 编出来。

切换就改 `gradle.properties` 里的 `ffmpegx.backend`，或者命令行加 `-Pffmpegx.backend=native`。

官方 ffmpeg-kit 2025 年初从 Maven Central 下架了，现在用的是社区维护的分支 `com.antonkarpenko:ffmpeg-kit-full`。

## 能干啥

格式转换、视频压缩（8 个预设 + 体积预估）、剪辑截取、音频处理（提取 / 变速 / 响度标准化）、GIF、拼接、字幕（烧录 / 提取 / 封装）、水印画中画、查看媒体信息、直接敲命令、任务队列。首页会列出这台设备实际支持的硬编硬解。

## 几个实话

- 一次只跑一个任务。FFmpeg 命令行一堆全局状态，串行是正确性要求，不是偷懒。
- 选文件有时会复制一份。SAF 给的管道不能 seek，FFmpeg 又需要，所以解不出真实路径时会复制到缓存。
- 输出不能直接写 SAF，得走「导出到媒体库」。
- release 包没签名，上架自己配。
- 转码还没在真机 / 模拟器上实跑过，目前只有单元测试兜底。

## 测试

85 个单元测试，盯着硬件规划、命令生成、进度解析、预设这几块最容易错的地方：

```bash
./gradlew :app:testDebugUnitTest
```

写测试的过程里揪出两个真 bug：丢视频流时还会生成 `-vf`（跟 `-vn` 打架），以及「质量优先」策略在规划器里根本没被判断。

## 目录

```
app/src/main/java/com/zhiwei/ffmpegx/
  core/hw/       硬件加速规划（MediaCodec 枚举 + 方案计算）
  core/cmd/      命令构造
  core/engine/   执行会话 + 进度解析
  core/media/    Uri → 真实路径
  core/task/     Room 队列 + 前台服务
  ui/            Compose 界面
app/src/kit/java/      ffmpeg-kit 后端
app/src/native/java/   自研 JNI 后端
scripts/               构建脚本
```

## 许可

GPL-3.0。默认后端用的 ffmpeg-kit-full 里带了 libx264 / libx265 这些 GPL 组件，整个 App 就得跟着 GPL。要是只用 LGPL 组件自己编，可以换。
