package com.zhiwei.ffmpegx.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ShaderBrush

/**
 * `android.graphics.RuntimeShader`（AGSL 着色器）的最小接入层。
 *
 * ## 为什么需要这一层
 *
 * 上游 catalog 的 `InteractiveHighlight` 里是这么写的：
 *
 * ```kotlin
 * import com.kyant.backdrop.RuntimeShader
 * import com.kyant.backdrop.asComposeShader
 * import com.kyant.backdrop.isRuntimeShaderSupported
 *
 * val shader = if (isRuntimeShaderSupported()) RuntimeShader("...") else null
 * ...
 * drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
 * ```
 *
 * 但这三个符号在 `backdrop` 已发布的 **1.0.6** 里**一个都没有**（逐字节核对了
 * 1.0.2 与 1.0.6 的全部 46 个顶层类，均不存在）—— 它们只存在于上游仓库的
 * 工作副本中，属于**尚未发版的新 API**，上游那份 catalog 在已发布版本上编不过。
 *
 * 好在它们包的东西是 **Android 平台自带的类**：`android.graphics.RuntimeShader`
 * （AGSL，API 33+），不是库自己实现的。所以不必等库发版。
 *
 * ## 为什么不需要 `asComposeShader`
 *
 * 这是本次核对中最关键的一条：Compose 的 `ShaderBrush` 有一个**公开的顶层工厂**，
 * 它直接接收 `android.graphics.Shader`：
 *
 * ```kotlin
 * // androidx.compose.ui.graphics.BrushKt —— 已从 AAR 字节码确认
 * // public static ShaderBrush ShaderBrush(android.graphics.Shader shader)
 * ShaderBrush(runtimeShader)
 * ```
 *
 * 也就是说平台着色器**本来就能直接进 Compose 的画笔**，
 * 上游那个 `asComposeShader()` 纯属多余包装。少一层抽象，少一处出错的可能。
 *
 * ## 为什么用 `@RequiresApi` 而不是在函数里判版本
 *
 * [RuntimeShader] 这个**类型**在 API 33 以下不存在。只要它出现在某个方法的
 * 签名或方法体里，ART 在验证/加载这个类时就会去解析它，低版本上直接抛
 * `NoClassDefFoundError` —— 这是 **Error 不是 Exception**，`try/catch` 拦不住，
 * 会连带把整个 `InteractiveHighlight` 类拖垮。
 *
 * 正确的隔离手段是：把用到该类型的方法单独标注 [RequiresApi]，
 * 让 ART 把整个方法的解析推迟到**真正被调用**时才做。这样只要低版本上
 * 永远不调它就不会出事 —— 调用方必须先过 [isRuntimeShaderSupported] 这道闸。
 */

/**
 * 这台设备能不能用 AGSL 着色器。
 *
 * 门槛是 API 33（Android 13）：`RuntimeShader` 从那一版才有。
 * 它本质上还依赖 GPU 的着色器编译能力，理论上存在「版本够但驱动不支持」的情况；
 * 不过 Android 13+ 的设备基本都满足，上游也没做更细的探测，这里保持一致。
 */
fun isRuntimeShaderSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 编译一段 AGSL 片段着色器。
 *
 * ⚠️ 只在 [isRuntimeShaderSupported] 为 true 时调用。
 * 返回 null 的调用点应走「纯色高光」降级分支，见 `InteractiveHighlight`。
 *
 * ⚠️ **返回类型刻意写成 `Any`，不是 `RuntimeShader`。**
 * `RuntimeShader` 在 API 33 以下不存在，而**任何持有它的变量的类型
 * 都会进所在类的描述符**。调用方（`InteractiveHighlight`）在字段初始化时
 * 拿到这个对象，如果返回类型是具体类，那个类型就会被拖进调用方的
 * 方法描述符，从而在低版本加载调用方类时就解析失败（`NoClassDefFoundError`）。
 * 返回 `Any` 就把这个类型彻底锁在本文件里。
 *
 * @param shader AGSL 源码。语法接近 GLSL ES，但入口固定为
 *        `half4 main(float2 coord)`；uniform 用 `uniform` 声明，
 *        颜色类型要写成 `layout(color) uniform half4`，
 *        这样 `setColorUniform` 才会做色彩空间转换。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun createRuntimeShader(shader: String): Any = RuntimeShader(shader)

/**
 * 把平台着色器包成 Compose 的画笔。
 *
 * 单独抽成函数有两个目的：
 * 1. 把 `@RequiresApi` 的影响面收在一个函数上；
 * 2. 让调用点不用同时 import `android.graphics.RuntimeShader` 和
 *    `androidx.compose.ui.graphics.ShaderBrush`，避免阅读时被两个同名概念绕晕。
 *
 * 接收者同样是 `Any`，理由见 [createRuntimeShader]：不让类型外泄。
 *
 * ⚠️ uniform 是**绑定在着色器对象上**的：同一个 [RuntimeShader] 实例在两帧之间
 * 复用没问题（每帧重设 uniform 即可），但**别拿一个实例同时画两处内容** ——
 * 后设的 uniform 会覆盖前一处。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Suppress("UNCHECKED_CAST")
fun Any.toBrush(): ShaderBrush = ShaderBrush(this as RuntimeShader)
