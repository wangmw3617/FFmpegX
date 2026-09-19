package com.zhiwei.ffmpegx.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 手指按上去时，在按点晕开的一团柔光。
 *
 * 取自上游 AndroidLiquidGlass 的 `catalog/utils/InteractiveHighlight.kt`。
 * 上游那份依赖三个**未发布**的 API（`RuntimeShader` / `asComposeShader` /
 * `isRuntimeShaderSupported` 在 backdrop 1.0.6 里都不存在），所以这里做了两处等效替换：
 *
 * 1. 用平台类 `android.graphics.RuntimeShader` 代替库的包装（见 [RuntimeShaderCompat.kt]）；
 * 2. 用 `drawRuntimeHighlight()` 这个普通绘制函数代替上游的内联 `drawWithContent` 块 ——
 *    因为 `@RequiresApi` 的方法必须能被单独隔离，不能和通用绘制代码混在一个 lambda 里。
 *
 * ## 光斑是「跟随」的，不是「铺满」的
 *
 * 上游把按点坐标也做成了动画（`positionAnimation` + 位置弹簧），
 * 拖动时高光会**追着手指跑**，并在松手后弹回起始点。
 * 如果只做透明度动画、位置固定，按在不同格子上会看到同一团光，
 * 那种「光是从我按的地方出来的」错觉就没了。
 *
 * ## 为什么用着色器而不是径向渐变
 *
 * `Brush.radialGradient` 的边缘是线性的，光斑边界能看出一圈规整的圆。
 * 这里的 AGSL 用 `smoothstep(radius, radius * 0.5, dist)`：
 * 从 `radius` 到 `radius/2` 之间做平滑插值，越靠中心越亮，
 * 到边缘处**导数为 0** —— 也就是完全没有硬边，像真的光晕。
 */
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    /** 把「逻辑位置」换算成「控件内的实际坐标」。默认原样返回。 */
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    /** 按压进度：0 = 松开（不画），1 = 完全按下（最亮） */
    private val pressProgressAnimation = Animatable(0f, 0.001f)

    /** 光斑中心。按下瞬间 `snapTo` 到按点，拖动时跟手，松手后弹回按点 */
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    /**
     * 着色器实例。只在设备支持时创建 —— 不支持（API < 33）时留 null，
     * 绘制时退化成一块纯白半透明矩形，不会崩也不会有突兀的圆斑。
     *
     * ⚠️ **类型刻意声明成 `Any?` 而不是 `RuntimeShader?`**。
     * 这不是偷懒，是必须的：字段类型会进类的**描述符（descriptor）**，
     * 而 ART 在**加载/校验**这个类时就会去解析描述符里出现的每个类型。
     * 一旦写成 `RuntimeShader?`，那么在任何 API < 33 的设备上，
     * 光是加载 [InteractiveHighlight] 这个类就会抛 `NoClassDefFoundError`
     * —— 这是 **Error**，`try/catch` 拦不住，整个类直接作废，
     * 连下面那条「纯色高光」降级分支都走不到。
     *
     * 声明成 `Any?` 后，`android.graphics.RuntimeShader` 只出现在
     * [drawRuntimeHighlight] 的方法体里（见该函数的说明），
     * 校验被推迟到真正调用它时，低版本上永远不会发生。
     *
     * 代价只是取值时多一次 `as` 强转 —— 类型安全由
     * 「只在 `isRuntimeShaderSupported()` 为 true 时创建」这个不变量保证。
     */
    private val shader: Any? =
        if (isRuntimeShaderSupported()) {
            createRuntimeShader(
                """
                uniform float2 size;
                layout(color) uniform half4 color;
                uniform float radius;
                uniform float2 position;

                half4 main(float2 coord) {
                    float dist = distance(coord, position);
                    float intensity = smoothstep(radius, radius * 0.5, dist);
                    return color * intensity;
                }
                """
            )
        } else {
            null
        }

    /**
     * 高光层。**挂在内容之下**（`drawWithContent` 先画高光再 `drawContent()`），
     * 这样图标与文字不会被白光洗淡。
     */
    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            val center = position(this.size, positionAnimation.value)
            val s = shader
            if (s != null) {
                // 注意这里传的是 Any 而不是 RuntimeShader：
                // 强转**必须在 drawRuntimeHighlight 内部**做，
                // 不能写在这一行 —— 这个 lambda 会被编译成
                // InteractiveHighlight 自己的合成方法，一旦这里出现
                // RuntimeShader 类型，就等于把校验又拉回了本类。
                drawRuntimeHighlight(s, this.size, center, progress)
            } else {
                drawRect(
                    Color.White.copy(alpha = 0.25f * progress),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawContent()
    }

    /**
     * 手势层。**挂在最上面那个 Box 上**（也就是滑块本身），
     * 因为它要吃掉整个底栏范围的按下与拖动，而不只是滑块那一格的宽度。
     */
    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}

/**
 * 用 AGSL 着色器画那团柔光。
 *
 * ⚠️ 这个函数必须是**独立的 `@RequiresApi` 函数**，不能把 `drawRect(ShaderBrush(...))`
 * 直接写在上面那个 `drawWithContent` lambda 里 ——
 * `RuntimeShader` 这个类型在 API 33 以下不存在，一旦出现在调用点的类型推断链上，
 * ART 在加载 [InteractiveHighlight] 这个类时就会去解析它并抛 `NoClassDefFoundError`，
 * 那个类会**整个用不了**（连带降级分支也走不到）。
 * 拆成独立函数后，验证器会把它的解析推迟到真正被调用时。
 *
 * @param shader 类型是 `Any` 而不是 `RuntimeShader` —— 道理同上：
 *        形参类型也在方法描述符里，写具体类型会让「隔离」失效。
 *        强转就放在本函数体内，那是整个类里唯一允许出现该类型的位置。
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
@Suppress("UNCHECKED_CAST")
private fun DrawScope.drawRuntimeHighlight(
    shader: Any,
    size: Size,
    center: Offset,
    progress: Float,
) {
    val runtimeShader = shader as android.graphics.RuntimeShader

    // ① 先铺一层极淡的整体提亮：让整个玻璃「亮起来」，
    //    而不是只有按点周围亮 —— 单靠光斑会显得像一块污渍。
    drawRect(
        Color.White.copy(alpha = 0.08f * progress),
        blendMode = BlendMode.Plus,
    )

    // ② 再叠着色器光斑
    runtimeShader.apply {
        setFloatUniform("size", size.width, size.height)
        setColorUniform("color", Color.White.copy(alpha = 0.15f * progress))
        // 半径取短边的 1.5 倍：足够把整个控件包住，不会看到光斑的边界
        setFloatUniform("radius", size.minDimension * 1.5f)
        // 夹进控件范围，否则拖到底栏外面时着色器会算到控件外的像素
        setFloatUniform(
            "position",
            center.x.fastCoerceIn(0f, size.width),
            center.y.fastCoerceIn(0f, size.height),
        )
    }
    drawRect(runtimeShader.toBrush(), blendMode = BlendMode.Plus)
}