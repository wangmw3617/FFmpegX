# 迁移到 ffmpeg-kit-next（单核心）

本文记录 FFmpegX 从「双后端（预编译 ffmpeg-kit AAR / 自研 JNI 编 fftools）」切换为
「单核心 ffmpeg-kit-next」的全部改动、理由与操作步骤。

---

## 1. 为什么换

原来的两个后端各有代价：

| | kit（原默认） | native（原可选） |
|---|---|---|
| 库 | `com.antonkarpenko:ffmpeg-kit-full:2.2.1` | 自研 JNI + 交叉编译 fftools |
| FFmpeg | 8.1.1 | 可自选 |
| 维护方 | **社区分支**（官方 `com.arthenica` 2025 年初下架） | 自己 |
| 进度 | 结构化 `StatisticsCallback` | 日志正则解析 |
| ffprobe | `getMediaInformation()` | 临时文件 + `-o` |
| 进程安全 | 天然安全 | 需自己拦 `exit()` |
| 构建 | 拉现成包 | 自己养交叉编译链 |

`arthenica/ffmpeg-kit-next` 是原作者 Taner Sener 的官方续作，把上面两个后端的优点合到了一起：

- **官方维护**，已发到 9.0.0（FFmpeg 9.0.1），持续更新；
- **同时提供**结构化进度回调与结构化 ffprobe —— 原先只有 kit 有；
- **内建 `ffkitsaf:` / `ffkitmem:` / `ffkitstream:` 协议** —— 原先要自己写 SAF 缓存中转；
- **不存在 `exit()` 杀掉宿主进程的问题** —— 原先要写 4 个 C 文件去拦。

代价只有一个：**它不发布二进制制品，AAR 必须自己构建**。

而这个代价恰好与「删掉 native 后端」相互抵消 —— 反正都要自己构建了，
留着两条编译链只会让维护成本翻倍。所以本项目选择**单核心**。

---

## 2. 改了什么

### 2.1 依赖与版本

- `gradle/libs.versions.toml`
  - 版本变量 `ffmpegKit = "2.2.1"` → `ffmpegKitNext = "9.0.0"`
  - 依赖 `com.antonkarpenko:ffmpeg-kit-full` → `com.arthenica:ffmpeg-kit-next`
  - 注释说明版本号规则（`9.0.0` ⇒ FFmpeg `9.0.1`）与 `--enable-gpl` 的必要性

### 2.2 仓库解析

- `settings.gradle.kts`
  - 新增本地 Maven 仓库，指向构建产物目录
  - 路径来源优先级：`gradle.properties` / `local.properties` → 环境变量
    `FFMPEGX_FFMPEG_KIT_NEXT_REPO` → 默认 `../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven`
  - 仓库用 `content { includeGroup("com.arthenica") }` 限定范围，
    避免它参与其它依赖的解析
  - 仓库不存在时**不构建失败**，只打印带操作指引的提示框

### 2.3 构建配置

- `app/build.gradle.kts`
  - 删除 `ffmpegx.backend` 开关、`ffmpegPrebuiltReady` 探测、`useNativeBackend` 分支
  - 删除 NDK / `externalNativeBuild` / `isJniDebuggable` 相关配置
  - 删除 `sourceSets` 注入 `src/kit/java` 或 `src/native/java` 的逻辑
  - `buildConfigField` 从 `FFMPEG_BACKEND` 改为 `FFMPEG_KIT_NEXT_VERSION`
  - 保留 ABI splits 与签名逻辑不变

- `gradle.properties`
  - 删除 `ffmpegx.backend`
  - 增加 `ffmpegx.ffmpegKitNextRepo` 的说明（可注释掉，用默认值）

### 2.4 后端实现

- `app/src/kit/java/.../KitBackend.kt` **移到** `app/src/main/java/com/zhiwei/ffmpegx/native/KitBackend.kt`
  - import 从 `com.antonkarpenko.ffmpegkit.*` 改为 `com.arthenica.ffmpegkit.*`
  - 新增 `supportsSaf` / `safParameterForRead` / `safParameterForWrite` / `releaseSafUrl`
  - 新增 `buildInfo()` 上报库版本、ABI、minSdk
  - 加载失败的 cause 链遍历保留（这条修复很重要，能看到真实的 dlopen 原因）

- `FfmpegBackend.kt` 接口新增 SAF 能力（默认返回 `null` / `false`，
  调用方据此回退到缓存中转）

- `FFmpegNative.kt` 门面
  - 删除 `FFmpegNative.kit` / `native` 双后端描述
  - 新增 `supportsSaf` / `safParameterForRead` / `safParameterForWrite` / `releaseSafUrl` / `buildInfo`

- `NativeLogSink.kt` → `AvLog.kt`：删掉只为 JNI 存在的 `NativeLogSink` 接口，
  保留 `AvLog` 常量

### 2.5 SAF：从「必须复制」到「直读直写」

`FileResolver.kt` 的解析顺序改为：

```
content:// Uri
  ├─ 1. ffkitsaf: 直读        ← 新增，零拷贝，不落地
  ├─ 2. 解析真实路径 _data     ← 原逻辑，不复制
  └─ 3. 复制到应用缓存         ← 兜底
```

`Resolved` 数据类相应调整：`ffmpegInput`（可直接拼命令行的字符串）
与 `realPath`（可空，走 saf 直读时为 null）。

**边界处理**：`ffprobe` 需要可 seek 的真实路径，而 `ffkitsaf:` 不落地。
所以「媒体信息」页在探测时会临时复制一份副本，**探测完立即删除** ——
转码主流程仍然走零拷贝。

### 2.6 删除的文件

```
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/ffmpegx_exit_compat.c
app/src/main/cpp/ffmpegx_jni.cpp
app/src/main/cpp/ffmpegx_runner.c
app/src/native/java/com/zhiwei/ffmpegx/native/BackendFactory.kt
app/src/native/java/com/zhiwei/ffmpegx/native/FfmpegJni.kt
app/src/native/java/com/zhiwei/ffmpegx/native/JniBackend.kt
app/src/kit/java/com/zhiwei/ffmpegx/native/BackendFactory.kt
app/src/kit/java/com/zhiwei/ffmpegx/native/KitBackend.kt（内容已移入 main）
app/src/main/java/com/zhiwei/ffmpegx/native/NativeLogSink.kt（→ AvLog.kt）
scripts/build-ffmpeg-android.sh
```

`app/src/kit/` 与 `app/src/native/` 两个目录整个移除。

### 2.7 混淆规则

`app/proguard-rules.pro` 增加 FFmpegKitNext 的保留规则。
这是**必须的**：它的 `NativeLoader` 走 JNI 加载，会话回调是从 native 侧反向
调上来的，类名或方法签名被混淆就会 `UnsatisfiedLinkError`。

---

## 3. 构建 AAR

ffmpeg-kit-next 只发源码。构建方式见 [ffmpeg-kit-next-build.md](./ffmpeg-kit-next-build.md)，
或直接用封装好的脚本：

```bash
./scripts/build-ffmpeg-kit-next.sh
```

**必须在 Linux / macOS / WSL2 上构建。** Windows 原生环境不可用
（上游的 `windows.sh` 只产出 Windows 目标，编不出 Android AAR）。

---

## 4. 许可证影响

| 构建选项 | ffmpeg-kit-next 本身 | 捆绑的 FFmpeg | 是否含 libx264/x265 |
|---|---|---|---|
| 默认 | LGPL-3.0 | LGPL-3.0 | ❌ |
| `--enable-gpl` | LGPL-3.0 | **GPL-3.0** | ✅ |

**本项目必须用 `--enable-gpl` 构建。** 原因：

- `CodecModels.kt` 里 H264 的 `swName = "libx264"`；
- `hasSoftwareEncoder` 决定了「兼容优先」策略下该编码是否可选；
- 不含 libx264/libx265 时，「兼容优先」（全软）会直接失效。

构建脚本默认已加 `--enable-gpl --enable-lib-x264 --enable-lib-x265`。
这种情况下整个 bundle 受 GPL-3.0 约束，**与本项目原有的 LICENSE 一致，无需改动**。

---

## 5. 验证清单

切完之后建议按这个顺序验：

1. `./gradlew testDebugUnitTest` —— 85 个单测应全绿（命令生成/规划逻辑没动过）
2. `./gradlew assembleDebug` —— 确认 AAR 能解析、APK 能打出
3. 装到真机，进「设置」页确认：
   - FFmpeg 版本显示 `9.x`
   - 执行核心显示 `ffmpeg-kit-next 9.0.0`
   - SAF 直读直写显示「支持」
4. 「媒体信息」页选一个文件，确认能出流信息
5. 跑一次「格式转换」，确认进度条动、能出结果
6. 跑一次「视频压缩」（两遍编码），确认多遍进度折算正常

第 4~6 步是原先 README 里标注「**未在真机或模拟器上完成实机验证**」的部分，
这次换了核心，正好一并实测。

---

## 6. 回退

改动落在独立提交里，回退即 `git revert` 该提交。
如果只想临时切回旧版依赖，把 `libs.versions.toml` 里的
`ffmpeg-kit-next` 换回 `com.antonkarpenko:ffmpeg-kit-full:2.2.1`、
并把 `KitBackend.kt` 的 import 改回 `com.antonkarpenko.ffmpegkit.*` 即可
（SAF 相关调用会返回 null，自动走缓存中转回退路径）。
