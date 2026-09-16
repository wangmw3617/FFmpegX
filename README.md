# FFmpegX

Android 原生 FFmpeg 前面板。把 FFmpeg 的完整命令行能力包装成可用的移动端工具，并在运行时根据设备
SoC 实际开放的 MediaCodec 能力，自动选择硬解 / 硬编通路。

- **语言 / UI**：Kotlin 2.1 + Jetpack Compose（Material 3）
- **最低版本**：Android 7.0（API 24）｜**目标版本**：Android 16（API 36）
- **架构**：单 Activity + Navigation Compose（类型安全路由）+ Hilt + Room + DataStore
- **FFmpeg**：双后端设计 —— 预编译 AAR（默认）或自研 JNI，两者执行的是同一条命令行

---

## 1. 怎么构建（最快路径）

```bash
./scripts/build-apk.sh
```

脚本会自动完成：定位 JDK → 下载安装 Android SDK（cmdline-tools / platform-36 / build-tools 36）
→ 下载 Gradle → 生成 Wrapper → 构建 APK → 复制到 `dist/`。

工具链全部落在项目下的 `.toolchain/`（约 2.5GB），不污染系统环境，删掉即可重置；
已存在的组件会跳过下载。**Windows 上双击 `build-apk.cmd` 也可以。**

常用参数：

```bash
./scripts/build-apk.sh --release              # release 包（未签名）
./scripts/build-apk.sh --abis arm64-v8a       # 只打一个 ABI，APK 从 ~124MB 降到 ~46MB
./scripts/build-apk.sh --backend native       # 改用自研 JNI（需先跑 build-ffmpeg-android.sh）
```

装到手机（本地构建后）：

```bash
adb install -r dist/ffmpegx-1.0.0-debug-arm64.apk
```

### 构建产物（APK）

源码仓库**不收录 APK 二进制**（见 `.gitignore`）。`dist/` 下的包由两种方式产出：

- **GitHub Actions 自动构建**：推送后到仓库 **Actions** 页查看 `Build FFmpegX` 任务，
  产物在 artifact `ffmpegx-debug-apk`；打 `v*` tag 会自动发 GitHub Release。
- **本地构建**：`./scripts/build-apk.sh`，产物落在 `dist/`。

两者都未签名（debug 签名），可直接侧载安装。

### 前置条件

只需要 **JDK 17+**（脚本会自己找；找不到时读 `JAVA_HOME`）。其余全部自动下载。
在 Windows 上需要 Git Bash（随 Git for Windows 安装）——`build-apk.cmd` 会自动定位它。

### 单元测试

```bash
./scripts/build-apk.sh --test     # 先跑测试再打包
# 或者
./gradlew :app:testDebugUnitTest
```

85 个用例，覆盖三块最容易出错、出错后最难查的逻辑：

| 测试类 | 用例 | 守什么 |
|---|---|---|
| `HardwarePlanCalculatorTest` | 15 | 硬件加速规划的三条硬规则（零拷贝前提、分辨率上限回落、各策略分支） |
| `CommandsTest` | 32 | 命令行生成（argv 顺序、`-vf`/`-vn` 互斥、`-af`/`copy` 互斥、atempo 串联、滤镜转义） |
| `ProgressParserTest` | 15 | 两种进度输出形态的解析与派生指标 |
| `PresetsTest` | 23 | 压缩预设的尺寸/码率换算、编码器名反查、展示格式化 |

这些测试是有实效的 —— 写它们的过程中就揪出了两个真 bug：

1. `plannedFilters` 没有考虑「丢弃视频流」的情况，会同时生成 `-vf` 与 `-vn`，
   FFmpeg 直接报 `Filtergraph was specified through the -vf option, but no output video stream`。
2. 「质量优先」策略在规划器里根本没被检查，只要有硬编就一定用硬编，
   与 UI 上写的「硬解 + 软编」不符。

---

## 2. 两个 FFmpeg 后端

上层（命令生成 `Commands`、硬件加速规划 `HardwarePlanner`、全部 UI）**完全不区分后端** ——
因为两个后端执行的都是原样的 ffmpeg 命令行。差异只在「谁来执行」。

| | `kit`（默认） | `native` |
|---|---|---|
| 来源 | Maven Central 上的预编译 ffmpeg-kit AAR | 本项目的 `app/src/main/cpp` |
| FFmpeg 版本 | 8.1.1 Full | 自行指定（脚本默认 7.1） |
| 构建前置 | 无 | 需在 Linux / macOS / WSL 跑交叉编译脚本 |
| 体积 | APK 增加约 62MB（三个 ABI） | 类似 |
| 进度 | 结构化 `Statistics` 回调 | 从日志统计行解析 |
| ffprobe | `FFprobeKit` 结构化返回 | CLI + 临时文件 |
| 编译选项 | 固定 | 完全可控 |

切换方式：`gradle.properties` 里的 `ffmpegx.backend`，或命令行 `-Pffmpegx.backend=native`。

**关于 ffmpeg-kit**：官方 `com.arthenica` 制品已于 2025 年初从 Maven Central 下架。本项目用的是
社区仍在维护的分支 `com.antonkarpenko:ffmpeg-kit-full`
（FFmpeg 8.1.1 Full，LGPL 3.0 / GPL 3.0 双许可，minSdk 24，已适配 16KB 页大小）。
它只提供 `libffmpegkit.so` 包装层与 `libav*.so`，不修改 FFmpeg 行为。

两个后端各自放在独立的 source set（`app/src/kit/java` 与 `app/src/native/java`），
编译期二选一，互不污染 classpath，也不会同时打进 APK。

---

## 3. 硬件加速

### 3.1 原则：只信运行时枚举，不信型号表

`MediaCodecScanner` 用 `MediaCodecList(REGULAR_CODECS)` 枚举设备上所有编解码器，
过滤别名，读取 `VideoCapabilities` 的最大分辨率 / 帧率 / 码率范围、是否支持 Surface、
支持的 Profile（据此判断 10bit / HDR）。

`SocProfile` 只做两件无风险的事：把 `Build.SOC_MODEL` / `Build.HARDWARE` 翻译成人类可读的平台名
（骁龙 8 Gen 3、天玑 9300 …），以及给出档位提示。**它不参与任何能力判定** ——
同一颗芯片在不同厂商 ROM 上开放的编码器集合可能不同。

### 3.2 `HardwarePlanner` 的三条硬规则

```
规则 1  -hwaccel_output_format mediacodec 会让解码器输出 MediaCodec 纹理帧，
        这种帧只能喂给 MediaCodec 编码器或 hwdownload。
        所以只有在「后面没有任何 CPU 滤镜」且「编码器也是 MediaCodec」时才能开零拷贝，
        否则必然报 "Impossible to convert between the formats"。

规则 2  只写 -hwaccel mediacodec（不带 output_format）时，FFmpeg 会自动把帧下载到系统内存，
        此时 CPU 滤镜可以正常工作 —— 这是最通用也最安全的硬解姿势。

规则 3  硬件编码器对分辨率/帧率有硬上限（来自 VideoCapabilities）。
        超出范围时回落软编，绝不硬上。
```

四种策略：

| 策略 | 解码 | 编码 | 适用 |
|---|---|---|---|
| 智能（默认） | MediaCodec | 无滤镜时零拷贝硬编，有滤镜时按能力选 | 大多数场景 |
| 速度优先 | MediaCodec | MediaCodec | 追求出片速度 |
| 质量优先 | MediaCodec | 软件（libx264） | 在意画质与体积 |
| 兼容优先 / 关闭 | 软件 | 软件 | 排障、老设备 |

### 3.3 命令生成与规划必须一致

`ToolViewModel.refresh()` 刻意跑两遍：

1. 用「全软件」的 dry plan 生成命令 → 只为拿到真实的滤镜结构；
2. 根据滤镜结构问规划器要真实方案 → 重新生成命令。

反过来做会出现「规划说可以零拷贝，但实际带了 `scale` 滤镜」的矛盾。
`Commands.plannedFilters()` 被规划与渲染两边共用，保证两边对「有没有滤镜」的判断永远一致。

### 3.4 Vulkan

设置里可开启 `-init_hw_device vulkan` 系列。当前只在**纯缩放**场景接管，
其它情况会明确提示已回退到 CPU 滤镜 —— 半吊子的 Vulkan 链路比不用更糟。

---

## 4. 自研 JNI 后端（可选）

如果要用 `--backend native`，需要先在 **Linux / macOS / WSL** 上编译 FFmpeg：

```bash
./scripts/build-ffmpeg-android.sh --abis arm64-v8a --profile standard
```

产物落到 `app/src/main/jniLibs/<abi>/` 与 `app/src/main/cpp/{ffmpeg,fftools,compat}/`，
之后 `--backend native` 即可启用。预计耗时：minimal 单 ABI 约 5 分钟，standard 三 ABI 约 30～50 分钟。

### 4.1 `exit()` 拦截 —— 最关键的一处改动

FFmpeg 的 `fftools` 是为「一次性命令行进程」写的：`exit_program()` 最终会调用 libc 的 `exit()`，
**直接把宿主进程干掉**。一次转码失败带走整个 App 是不可接受的。

做法（`ffmpegx_exit_compat.c`）：

- `libffmpegcmd.so` / `libffprobecmd.so` 用 `-Wl,-Bsymbolic` 链接，
  于是库内部对 `exit()` 的引用绑定到我们自己提供的同名实现，而不是 libc 的；
- 自己的 `exit()` 在 armed 状态下 `longjmp` 回调用点；未 armed 时退回 `_exit()`，行为与 libc 一致；
- FFmpeg 在调用 `exit()` 之前，`exit_program()` 已经先执行了 `program_exit` 回调
  （即 `ffmpeg_cleanup` / `ffprobe_cleanup`），所以资源是被正确释放的。

**这套机制不改动任何 FFmpeg 源码，也不依赖版本相关的内部符号。**

### 4.2 两个 CLI 库 + 运行时 dlopen

`ffmpeg.c` 与 `ffprobe.c` 都会定义 `program_name` / `program_birth_year` 等全局符号，
且各自的 `fftools` 依赖集不同（`ffprobe` 不需要 `graph/`，`ffmpeg` 需要）。
因此拆成两个独立 `.so`，由 `libffmpegx.so` 在运行时 `dlopen(RTLD_LOCAL)` 精确取符号，
从根上避免符号抢占。

### 4.3 取消

`ffmpeg.c` 的 `main()` 内部会调用 `term_init()` 为 SIGINT 注册处理器，处理器把
`received_sigterm` 置 1，`transcode()` 主循环随即退出并走正常 cleanup。
所以「取消」= 向执行线程 `raise(SIGINT)`，**不需要访问任何 static 全局变量**。

取消是协作式的，`FFmpegEngine` 在取消后会等待底层真正退出（最多 20 秒）才返回，
否则下一个任务会被上一个的残留状态污染。

---

## 5. 功能清单

| 功能 | 说明 |
|---|---|
| **格式转换** | 容器（MP4/MKV/WebM/MOV/TS）、编码器、CRF/ABR、缩放、帧率、音频编码 |
| **视频压缩** | 8 种场景预设 + 体积预估；两遍编码（软编时）把体积控准 |
| **剪辑截取** | 起止时间轴；`-c copy` 无损剪切 / 逐帧精确重编码 |
| **音频处理** | 提取、换轨、音量、变速（自动串联 atempo）、淡入淡出、EBU R128 响度标准化 |
| **GIF 制作** | 两遍调色板（palettegen + paletteuse）、抖动算法、循环次数 |
| **视频拼接** | concat demuxer 无损拼接 / filter_complex 通用重编码 |
| **字幕处理** | 烧录（subtitles 滤镜 + 样式覆盖）、提取内嵌字幕、封装外挂字幕 |
| **水印 / 画中画** | 图片水印（位置/大小/透明度/显示时段）、PiP、左右分屏、上下分屏 |
| **媒体信息** | ffprobe 全字段解析 + 原始 JSON + 一键导出到媒体库 |
| **命令行** | 直接写 ffmpeg 参数，实时日志 |
| **任务队列** | 串行执行、进度、取消、重跑、前台服务保活、完成通知 |
| **设备能力** | 首页展示运行时枚举出的硬编/硬解清单与平台提示 |

---

## 6. 目录结构

```
FFmpegX/
├── build-apk.cmd                   Windows 双击入口
├── scripts/
│   ├── build-apk.sh                一键构建 APK（含 SDK / Gradle 自动准备）
│   ├── build-ffmpeg-android.sh     交叉编译 FFmpeg（仅 native 后端需要）
│   └── check-imports.py            编译前静态自检：跨包导入错误一次性全揪出来
├── app/src/
│   ├── main/
│   │   ├── cpp/                    自研 JNI 层（native 后端）
│   │   └── java/com/zhiwei/ffmpegx/
│   │       ├── native/             后端门面 + 接口
│   │       ├── core/
│   │       │   ├── hw/             SoC 画像 · MediaCodec 枚举 · 硬件方案规划
│   │       │   ├── cmd/            命令构造器 + 各功能 Spec + 统一渲染
│   │       │   ├── engine/         FFmpeg 会话执行 · ffprobe 解析 · 进度解析
│   │       │   ├── media/          Uri→真实路径解析 · MediaStore 导出
│   │       │   ├── model/          媒体信息模型 · 压缩预设 · 容器定义
│   │       │   ├── settings/       DataStore 设置
│   │       │   └── task/           Room 任务库 · 队列 · 前台服务
│   │       ├── ui/                 主题 · 组件 · 导航 · 工作台 · 各页面
│   │       └── di/                 Hilt 模块
│   ├── kit/java/…/native/          KitBackend（ffmpeg-kit 实现）
│   └── native/java/…/native/       JniBackend + FfmpegJni（自研实现）
└── .toolchain/                     构建工具链（自动生成，可删）
```

---

## 7. 已知限制（如实说明）

1. **默认后端依赖第三方预编译包**。官方 ffmpeg-kit 已下架，本项目用的是社区维护分支。
   如果对供应链有要求，请用 `--backend native` 自己编译。
2. **HEVC / AV1 的软件编码器**：`kit` 后端（Full 构建）包含 libx265，`native` 后端的脚本默认只带 libx264，
   需要软编 HEVC 请在 `build-ffmpeg-android.sh` 的 deps 里加 libx265。
3. **一次只跑一个任务**。FFmpeg CLI 有大量进程级全局状态，串行是正确性要求而不是偷懒。
4. **两遍编码只对软件编码器有效**。MediaCodec 不支持 `-pass`，硬编时该选项会自动失效。
5. **Vulkan 滤镜仅覆盖纯缩放**。带 overlay / 字幕的链路仍走 CPU。
6. **文件选择会复制**。SAF 给出的管道不可寻址，FFmpeg 需要 `seek()`，
   所以解析不出真实路径时会复制到应用缓存（`Android/data/<pkg>/cache/work/inputs`）。
   大文件首次载入会慢一点。
7. **不支持直接写 SAF 输出**。输出走真实文件路径，需要「导出到媒体库」这一步。
8. **release 包未签名**。上架前请自行配置签名。

---

## 8. 后续可做的扩展

- 接入 Media3 `Transformer` 作为纯硬编轻量通路（不需要 FFmpeg 的简单场景）
- `VideoCapabilities.getSupportedPerformancePoints()`（API 29+）在多颗硬件编码器之间择优
- 多进程并发队列
- 自定义滤镜链可视化编辑
- 字幕样式实时预览

---

## 9. 隐私

所有处理都在本机完成。App 不声明 `INTERNET` 权限。`native` 后端的 FFmpeg 以
`--disable-network` 编译；`kit` 后端受其构建配置限制，但 App 本身没有网络权限，
无法发起任何请求。
