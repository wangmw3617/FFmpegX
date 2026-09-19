package com.zhiwei.ffmpegx.ui.theme

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * Liquid Glass（毛玻璃）基础设施。
 *
 * 用的是 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
 * 的 `backdrop` 库。它**只提供底层绘制原语**，不含任何现成组件 —— 卡片、导航栏
 * 都要自己按 `drawBackdrop` 拼。本文件的写法逐项对照了上游 `catalog` 模块的
 * `components/LiquidBottomTabs.kt`、`components/LiquidButton.kt` 与
 * `BackdropDemoScaffold.android.kt`。
 *
 * ## 三个概念，缺一不可
 *
 * 1. [rememberAppBackdrop] 建一个「背景层」；
 * 2. `Modifier.layerBackdrop(backdrop)` 标记**哪些内容会被玻璃采样**；
 * 3. `Modifier.liquidGlass(...)` 才是玻璃本身。
 *
 * **采样层与玻璃层必须是兄弟节点，且玻璃排在后面。**
 * 如果玻璃被包在采样层内部，它采到的是自己 → 递归，什么都画不出来。
 *
 * ## ⚠️ 采样范围必须尽量小，否则全局卡顿
 *
 * 这是本项目性能上最容易踩的坑，值得写清楚。
 *
 * `layerBackdrop` 挂在哪一层，那一层就会被**整套重录进一张 GraphicsLayer**，
 * 而且**每次重绘都要重录**（见上游 `LayerBackdropNode.draw()`：
 * `drawContent()` 之后立刻 `recordLayer(...)`）。所以采样层里放的东西越多、
 * 变化越频繁，每帧的代价就越高。
 *
 * 本文件原先的实现是**把整个 NavHost 的页面内容一起纳入采样层**（理由是
 * 「内容滚到玻璃下面时玻璃能真的把它糊掉」）。代价是：任何滚动、任何动画、
 * 任何列表项刷新，都会触发一次**整屏图层重录 + 全屏模糊重采样** ——
 * 直接表现为全局掉帧。这不是小问题，是卡顿的头号来源。
 *
 * 上游 `catalog` 的做法正好相反：采样层**只包一张静态壁纸图**
 * （`Image(..., Modifier.layerBackdrop(backdrop).fillMaxSize())`），
 * 玻璃组件全部放在它外面。静态图层重录代价极低，模糊采样的内容也没变，
 * 效果反而更稳定。
 *
 * 现在本项目采用**两层折叠**：
 *
 * - [AppBackground] 只画「基色 + 色斑」，并作为**唯一采样源**；
 * - [AppContent] 放页面内容，**不参与采样**，滚动与动画不再触发重录。
 *
 * 视觉上的取舍：玻璃采样的是底色与色斑，而不是滚动中的文字。
 * 在「内容会从玻璃下穿过」和「滚动不掉帧」之间，这里明确选后者 ——
 * 悬浮底栏是不透明的玻璃，内容被它挡住本来就看不见。
 *
 * ## 为什么背景要有色斑
 *
 * 玻璃的本质是「模糊并折射它下面的东西」。纯色模糊前后一模一样，
 * 一层平滑渐变模糊后也几乎看不出变化 —— 看上去就只是普通半透明块。
 * 所以 [BackgroundGlow] 在基色上叠了几个很淡的径向色斑：
 * 模糊能看出层次，玻璃边缘的折射（lens）也才有东西可弯。
 *
 * ## 版本约束
 *
 * 停在 **1.0.2**，三个理由（见 libs.versions.toml 里的详细数据）：
 *
 * 1. 2.0.0 起 AAR 元数据要求 `minCompileSdk=37`，本项目是 36
 *    （AGP 8.10.1 也不支持 37）→ 只能停在 1.x；
 * 2. 1.0.3 ~ 1.0.6 是用 Kotlin 2.3.x 编的，会把 Kotlin 顶到 2.3，
 *    进而逼着 Hilt 升到 2.60，而 Hilt 2.60 又要求 AGP ≥9 —— 连锁升版；
 * 3. 1.0.2 用 Kotlin 2.2.21 编，且**不依赖 shapes**，AAR 元数据是
 *    `minCompileSdk=1`，是本项目唯一能全链路不动其他依赖的版本。
 *
 * `highlight` / `shadow` / `innerShadow` / `lens(chromaticAberration)` 这几个
 * API 在 1.0.2 与上游当前版本之间逐项核对过，签名一致。
 */
@Composable
fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 应用背景层，同时是**唯一的玻璃采样源**。
 *
 * 它只画底色与色斑，**不含任何页面内容** —— 这是刻意的（见文件头「采样范围」
 * 一节）。它铺满全屏，由 [AppContent] 在其上叠加真正的界面。
 */
@Composable
fun AppBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        BackgroundGlow()
    }
}

/**
 * 应用内容层。
 *
 * 与 [AppBackground] 是**兄弟**关系（都由调用方放进同一个 `Box`，且本层在后），
 * 这样内容才能盖住背景；但本层**不带 `layerBackdrop`**，所以不参与采样、
 * 不触发重录。
 */
@Composable
fun AppContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()
    }
}

/**
 * 基色 + 两个径向色斑。
 *
 * ## 颜色全部取自当前主题，不写死
 *
 * 主题默认开着「动态取色」（`dynamicColor = true`），主色会跟着壁纸走。
 * 早先这里的色斑写死了品牌蓝 + 青绿 —— 壁纸一旦不是蓝色系，
 * 背景色斑和界面主色就会撞在一起，整屏发浑，这正是「看着丑」的一大来源。
 * 改成从 `colorScheme` 取 primary / secondary，就永远和界面同源。
 *
 * ## 基色为什么用 surfaceContainerLow
 *
 * 卡片用的是 `surface`，页面背景必须和它差一档，否则卡片会「融进」背景里、
 * 看不出层次。`surfaceContainerLow` 正好比 `surface` 低一档，且在浅色/深色
 * 下都保持这个相对关系（它由色调阶梯固定，动态取色时也一样）。
 *
 * ## 色斑为什么必须很淡
 *
 * 它的唯一作用是给玻璃提供「可被模糊出层次」的像素。
 * 太浓会喧宾夺主，把信息阅读区搅花。
 *
 * ## 色斑位置为什么避开屏幕中下部
 *
 * 两个色斑分别落在左上与右下，是为了让**悬浮底栏**（屏幕底部）与**顶栏**区域
 * 各自压到一处色斑边缘。玻璃压在色斑渐隐处时，模糊前后的层次差最明显，
 * 折射也最容易看出来 —— 这正是上游 demo 用整张壁纸做采样源想要的效果。
 */
@Composable
private fun BackgroundGlow() {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceContainerLow
    val blobA = scheme.primary
    val blobB = scheme.secondary
    val alphaA = if (dark) 0.34f else 0.18f
    val alphaB = if (dark) 0.24f else 0.13f

    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(blobA.copy(alpha = alphaA), Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.04f),
                radius = size.minDimension * 1.05f,
            ),
            size = size,
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(blobB.copy(alpha = alphaB), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.78f),
                radius = size.minDimension * 0.95f,
            ),
            size = size,
        )
    }
}

/**
 * 把任意容器变成一块液态玻璃。
 *
 * 效果组合对照上游 `LiquidBottomTabs`：
 *
 * | 效果 | 上游底栏取值 | 本函数默认 | 作用 |
 * |------|------------|-----------|------|
 * | `blur` | 8dp | 8dp | 背景模糊，越大越「厚」 |
 * | `lens` | 24dp / 24dp | 22dp / 22dp | 边缘折射，玻璃最标志性的观感 |
 * | `highlight` | `Highlight.Default` | 可选（默认开） | 上缘高光，让玻璃有「厚度」 |
 * | `shadow` | 仅按压时 | 可选（默认开） | 外投影，把玻璃从背景上「托起来」 |
 * | `innerShadow` | 仅按压时 | 可选（默认关） | 内投影，按压时的「凹陷」感 |
 *
 * 早先本项目只用了 `vibrancy + blur + lens` 三件套，而且 blur 给到 22dp ——
 * 这恰好是上游**的两倍多**。模糊过头会把背景色斑糊成一片均匀色，
 * 折射与高光都失去了参照，看上去就是「一块半透明的塑料板」，
 * 这正是「效果不如原项目」的直接原因。
 *
 * @param backdrop 采样源。为 null 时退化成普通半透明表面 —— 这样即使玻璃层
 *        没准备好，界面也不会变成透明的「空洞」。
 * @param shape 玻璃的形状。**必须是 [CornerBasedShape]**（`RoundedCornerShape` /
 *        `CutCornerShape` / `CircleShape`）才能拿到折射效果；传 `RectangleShape`
 *        这类非圆角形状不会崩，但会自动退化成「模糊 + 提亮」，见下方说明。
 * @param blurRadius 背景模糊半径，越大越「厚」。默认 8dp 与上游底栏一致。
 * @param lensAmount 边缘折射强度，这是液态玻璃最标志性的观感。
 * @param withHighlight 是否叠加边缘高光。玻璃没有高光会显得「平」。
 * @param withShadow 是否叠加外投影。悬浮元素（底栏、浮动按钮）需要它才有浮起感。
 * @param withInnerShadow 是否叠加内投影。一般只在按压态临时开启。
 * @param highlightAlpha 高光强度。按压动画需要一个可变的 alpha，所以开出来。
 * @param innerShadowAlpha 内投影强度，同理由按压动画驱动。
 * @param pressProgress 按压进度 0~1。**驱动折射与内投影的强度** ——
 *        上游底栏的 lens 是 `24dp * pressProgress`，即「平时不折射、
 *        按下时才折射」，这一层动态是液态玻璃「活」的关键。传 1f 表示
 *        常驻折射（静态玻璃），传 0f 表示不折射。
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    blurRadius: Dp = 8.dp,
    lensAmount: Dp = 22.dp,
    withHighlight: Boolean = true,
    withShadow: Boolean = false,
    withInnerShadow: Boolean = false,
    highlightAlpha: Float = 1f,
    innerShadowAlpha: Float = 0f,
    pressProgress: Float = 1f,
): Modifier {
    // RenderEffect 是 API 31 才有的。更低版本里 blur / lens 都是空操作，
    // 玻璃会退化成一块「透明的洞」—— 还不如直接用半透明表面兜底。
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape)
    }
    // ⚠️ 形状判断要在外面做：`lens` 内部会去读 `shape` 的圆角半径，
    // 拿不到就直接 `throw UnsupportedOperationException(...)`（见 Lens.kt
    // 的 `throwUnsupportedSDFException()`）：
    //
    //     val cornerRadii = when (val shape = shape) {
    //         is RoundedRectangularShape -> ...
    //         is AbsoluteRoundedCornerShape -> ...
    //         is CornerBasedShape -> ...
    //         else -> null
    //     }
    //     val effect = if (cornerRadii != null) { ... } else { throwUnsupportedSDFException() }
    //
    // 关键在于它**发生在绘制阶段**：不是「效果没生效」，而是主线程直接抛异常。
    // 传一次 RectangleShape 就会崩，所以这里必须先判断形状，
    // 拿不到圆角就只画模糊与提亮 —— 视觉上少一层折射，但绝不崩。
    val supportsLens = shape is CornerBasedShape
    val progress = pressProgress.coerceIn(0f, 1f)

    // ⚠️ `blur` / `lens` 的长度参数是**像素**（Float），而它们的接收者是
    // `BackdropEffectScope` —— 该接口实现了 `Density`，所以 `toPx()` 只能
    // 在 `effects` lambda 内部调用。在外面写 `blurRadius.toPx()` 会因为没有
    // Density 接收者而编译不过（上游也是清一色写成 `24f.dp.toPx()` 放在 lambda 里）。
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // vibrancy 先把背景色提亮饱和，否则玻璃会显得发灰
            vibrancy()
            blur(blurRadius.toPx())
            // 折射强度随按压进度变化：静止时几乎不折射，按下才「弯」起来。
            // 静态场景（pressProgress 恒为 1f）得到的是恒定的折射，与旧行为等价。
            if (supportsLens && progress > 0f) {
                val refraction = lensAmount.toPx() * progress
                if (refraction > 0f) {
                    lens(refraction, refraction)
                }
            }
        },
        highlight = if (withHighlight) {
            { Highlight.Default.copy(alpha = highlightAlpha * progress) }
        } else {
            null
        },
        shadow = if (withShadow) {
            { Shadow(alpha = progress) }
        } else {
            null
        },
        innerShadow = if (withInnerShadow) {
            { InnerShadow(radius = 8f.dp, alpha = innerShadowAlpha) }
        } else {
            null
        },
    )
}
