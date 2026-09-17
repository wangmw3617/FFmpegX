package com.zhiwei.ffmpegx.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * 1. [rememberLayerBackdrop] 建一个「背景层」；
 * 2. `Modifier.layerBackdrop(backdrop)` 标记**哪些内容会被玻璃采样**
 *    （没有这一步，玻璃层拿不到任何像素，画出来是全透明的）；
 * 3. `Modifier.drawBackdrop(backdrop, shape, effects)` 才是玻璃本身。
 *
 * ## 为什么必须配一层渐变背景
 *
 * 玻璃的本质是「模糊它下面的东西」。如果背景是纯色，模糊前后一模一样，
 * 看上去就只是普通半透明块 —— 完全看不出液态玻璃的质感。
 * 所以 [AppBackground] 铺了一层很淡的品牌色渐变，给玻璃提供可折射的层次。
 *
 * ## 版本约束
 *
 * 必须停在 **1.x**：backdrop 2.0.0 起 AAR 元数据要求 `minCompileSdk=37`，
 * 而本项目是 36（AGP 8.10.1 也不支持 37）。1.0.6 与 shapes 1.2.0 都只要求 36。
 */
@Composable
fun rememberAppBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 应用背景层。
 *
 * 这层渐变同时也是 `layerBackdrop` 的采样源：它被标记为「背景」，
 * 上层的玻璃卡片就能从它身上取色与模糊。
 */
@Composable
fun AppBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    // 淡到几乎看不出是渐变，但足够让玻璃产生层次 —— 太浓会喧宾夺主
    val brush = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF101725),
                Color(0xFF0B1220),
                Color(0xFF0E1A1C),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFF7F9FF),
                Color(0xFFFCFCFF),
                Color(0xFFF4FAF9),
            ),
        )
    }

    Box(modifier.fillMaxSize()) {
        // 被玻璃采样的那一层：渐变背景 + 全部页面内容
        Box(
            Modifier
                .fillMaxSize()
                .background(brush)
                .layerBackdrop(backdrop),
        ) {
            content()
        }
    }
}

/**
 * 把任意容器变成一块液态玻璃。
 *
 * @param backdrop 为 null 时退化成普通半透明表面 —— 这样即使玻璃层没准备好，
 *        界面也不会变成透明的「空洞」。
 * @param shape 玻璃的形状（圆角矩形 / 胶囊等）
 * @param blurRadius 背景模糊半径，越大越「厚」
 * @param lensAmount 边缘折射强度，这是液态玻璃最标志性的观感
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    blurRadius: Dp = 14.dp,
    lensAmount: Dp = 10.dp,
): Modifier = if (backdrop == null) {
    this.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape)
} else {
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // vibrancy 先把背景色提亮饱和，否则玻璃会显得发灰
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensAmount.toPx(), lensAmount.toPx() * 2f)
        },
    )
}
