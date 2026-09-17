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

/**
 * Liquid Glass（毛玻璃）基础设施。
 *
 * 用的是 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
 * 的 `backdrop` 库。它**只提供底层绘制原语**，不含任何现成组件 —— 卡片、导航栏
 * 都要自己按 `drawBackdrop` 拼。
 *
 * ## 三个概念，缺一不可
 *
 * 1. [rememberAppBackdrop] 建一个「背景层」；
 * 2. `Modifier.layerBackdrop(backdrop)` 标记**哪些内容会被玻璃采样**；
 * 3. `Modifier.liquidGlass(...)` 才是玻璃本身。
 *
 * **采样层与玻璃层必须是兄弟节点，且玻璃排在后面。**
 * 如果玻璃被包在采样层内部，它采到的是自己 → 递归，什么都画不出来。
 * 所以 [AppBackground] 只负责「渐变 + 页面内容」，玻璃条要写在它外面。
 *
 * ## 为什么背景要有色斑
 *
 * 玻璃的本质是「模糊并折射它下面的东西」。纯色模糊前后一模一样，
 * 一层平滑渐变模糊后也几乎看不出变化 —— 看上去就只是普通半透明块。
 * 所以 [AppBackground] 在基色上叠了几个很淡的径向色斑：
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
 * 1.0.2 与本文件原先按 1.0.6 写的 API 逐项核对过，完全一致。
 */
@Composable
fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 应用背景层，同时也是玻璃的采样源。
 *
 * 它铺满全屏，并且**把 [content] 一起纳入采样范围** —— 这样页面内容滚到
 * 玻璃条下面时，玻璃能真的把它糊掉，而不是只糊一层背景色。
 */
@Composable
fun AppBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        BackgroundGlow()
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
 * @param backdrop 采样源。为 null 时退化成普通半透明表面 —— 这样即使玻璃层
 *        没准备好，界面也不会变成透明的「空洞」。
 * @param shape 玻璃的形状。**必须是 [CornerBasedShape]**（`RoundedCornerShape` /
 *        `CutCornerShape` / `CircleShape`）才能拿到折射效果；传 `RectangleShape`
 *        这类非圆角形状不会崩，但会自动退化成「模糊 + 提亮」，见下方说明。
 * @param blurRadius 背景模糊半径，越大越「厚」
 * @param lensAmount 边缘折射强度，这是液态玻璃最标志性的观感
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    blurRadius: Dp = 14.dp,
    lensAmount: Dp = 10.dp,
): Modifier {
    // RenderEffect 是 API 31 才有的。更低版本里 blur / lens 都是空操作，
    // 玻璃会退化成一块「透明的洞」—— 还不如直接用半透明表面兜底。
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape)
    }
    // ⚠️ lens 需要形状的圆角半径来构造 SDF，库里是这么写的：
    //
    //     val shape = shape as? CornerBasedShape ?: return null
    //     ...  if (cornerRadii != null) { 用半径画折射 } else { 抛异常 }
    //
    // 拿不到半径就直接 `throw UnsupportedOperationException(
    // "Only CornerBasedShape is supported in lens effects.")`。
    //
    // 关键在于它**发生在绘制阶段**：不是「效果没生效」，而是主线程直接崩、
    // 应用秒退。传一次 RectangleShape 就会闪退，所以这里必须先判断形状，
    // 拿不到圆角就只画模糊与提亮 —— 视觉上少一层折射，但绝不崩。
    val supportsLens = shape is CornerBasedShape
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // vibrancy 先把背景色提亮饱和，否则玻璃会显得发灰
            vibrancy()
            blur(blurRadius.toPx())
            if (supportsLens) {
                lens(lensAmount.toPx(), lensAmount.toPx() * 2f)
            }
        },
    )
}
