package com.zhiwei.ffmpegx.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃底栏（上游 `catalog/components/LiquidBottomTabs.kt` 的移植版）。
 *
 * ## 三层叠出来的「液态」
 *
 * 这是整个组件的核心，也是原先那版「别扭」的根源 —— 老版本只有
 * 「一块玻璃 + 一条滑动的胶囊指示器」两层，胶囊只是换了个位置，
 * 跟背景没有任何光学交互。上游这套是**三层**：
 *
 * ```
 * ① 底板   blur + lens，画 containerColor        ← 一块厚玻璃
 * ② 内容层 与①逐像素重合，alpha=0，                      ← 只贡献「折射」不贡献「画面」
 *          layerBackdrop 到自己的 tabsBackdrop，
 *          整体 tint(accentColor)，
 *          其 lens 强度随按压力度 0 → 24dp
 * ③ 滑块   独立一层，采样「背景 ⊕ tabsBackdrop」        ← 凸透镜，跟手拖
 *          （rememberCombinedBackdrop），
 *          lens 随按压力度变化 + 色散
 * ```
 *
 * ② 是整套效果的**秘诀**，也是最反直觉的一步：
 * 深色底栏上直接用强调色画图标会很脏（对比度不够），
 * 所以图标本身照常用主题前景色画在 ① 里；
 * 另外单独复制一份**完全相同的排布**、整体染成强调色、设成完全透明，
 * 再让它的玻璃折射去扭曲下层的底板 —— 于是「强调色的光」就顺着
 * 玻璃的折射纹路渗出来了。松手时 lens 强度归零，光也就散了。
 *
 * ② 必须 `fillMaxWidth().padding(horizontal = 4.dp)` 且 `height(56.dp)`，
 * 与 ① 的 `height(64.dp).fillMaxWidth().padding(4.dp)` 得到**同一个矩形**
 * （都是左右各内缩 4dp、高 56dp）。错开一个像素，折射就会错位、边缘发虚。
 *
 * ## 为什么滑块要采「组合背景」
 *
 * 滑块是浮在底板**上面**的，它下面其实是那层底板。
 * 用 `rememberCombinedBackdrop(backdrop, tabsBackdrop)` 把两者合起来当采样源，
 * 滑块才能既折到页面背景、又折到底板的折射纹理 —— 这才有了
 * 「一层玻璃叠在另一层玻璃上」的厚度感。
 *
 * @param selectedTabIndex 当前选中项。**传 getter 而不是值**：
 *        组件内部要靠 `snapshotFlow` 侦听它变化来同步滑块位置
 *        （见 [LaunchedEffect]，`drop(1)` 是为了跳过首次组合时的值）。
 * @param onTabSelected 用户点击或拖动落到新格子时回调
 * @param backdrop 页面背景采样源，通常来自 [rememberAppBackdrop]
 * @param tabsCount 槽位数量，必须与 [LiquidBottomTab] 的个数一致
 * @param selectedScale 按下时内容的额外放大比例。0.2 表示「放大到 1.2 倍」。
 *        传 0 可以关掉这层反馈（但那样按压就只剩玻璃在动了）
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    selectedScale: Float = 0.2f,
    content: @Composable RowScope.() -> Unit,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)

    /** ② 内容层自己的采样源：只装它自己，用来把强调色「折射」出去 */
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount }

        // 整条底栏的横向位移。拖动时它让底板与内容层一起轻微「让开」，
        // 松手弹回 0 —— 这是「底板是活的」那点意思，幅度很小（最多 4dp），
        // 只为消掉纯平移的呆板感。
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    // EaseOut 让「刚开始拖」时让位最多、越拖越收敛，手感更自然
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) { mutableIntStateOf(selectedTabIndex()) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 按下时滑块横向撑大：56 → 78（约 1.39 倍），
                // 让它「挤到」相邻格子之间，看起来像被手指压扁摊开
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                },
            )
        }

        // 外部（导航）改了选中项 → 把滑块吸过去
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { index -> currentIndex = index }
        }
        // 内部（点击/拖动）改了选中项 → 通知外部。
        // drop(1) 跳过首次组合时 currentIndex 的初始值，否则会在进入页面时
        // 白白触发一次 onTabSelected。
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    // 光斑跟着滑块中心走：滑块中心 = (进度 + 0.5) 个格子宽
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f,
                    )
                },
            )
        }

        // ———————————————————— ① 底板 ————————————————————
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        // 按下时整块底板轻微胀大（约 +16dp 宽），像被从下面顶了一下
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        // ———————————————————— ② 内容层（强调色折射） ————————————————————
        //
        // alpha(0f) 是刻意为之：这一层**不显示任何画面**，
        // 它的存在只为让 lens 去扭曲下面那层底板。
        // clearAndSetSemantics 是必要的 —— 否则 TalkBack 会把每个标签读两遍。
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                // 按下时内容一起放大，且比滑块稍大的幅度，让「折射出的光」有体积感
                lerp(1f, 1f + selectedScale, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            // 关键：默认强度 0，按下去才涨到 24dp。
                            // 所以松手时这一层完全「隐形」，不会平白给底栏加一层色偏
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }

        // ———————————————————— ③ 滑块 ————————————————————
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dampedDragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                // 手势层挂在滑块上：它要吃掉整个底栏区域的按下与拖动（因为
                // padding 之外的部分仍在 Row 的范围内，且 pointerInput 会
                // 参与整条底栏的命中测试），而不只是滑块那一格
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    // 组合采样源：滑块下面既是页面背景、又是底板本身的折射，
                    // 合起来才折得对
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // chromaticAberration：边缘色散。
                        // 真玻璃的厚边缘会把不同波长折到不同角度，于是边缘泛彩边 ——
                        // 这是「液态玻璃」最容易一眼认出的特征，也是最容易被忽略的细节
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        InnerShadow(radius = 8f.dp * dampedDragAnimation.pressProgress, alpha = dampedDragAnimation.pressProgress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 甩动时横向拉长、纵向压扁（体积守恒的错觉），
                        // 上限 ±20%，再多就变形过头了
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        // 一层「底色」：松开时它可见（滑块是块实心胶囊），
                        // 按下去时淡出，让位给玻璃折射 + 高光 + 阴影
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress,
                        )
                        // 按下时再压一层极暗，做出「摁进去」的凹陷
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

/**
 * 底栏的单个槽位。
 *
 * 内容默认居中竖排（图标在上、文字在下），点击区就是整个槽位。
 * 放大倍数由 [LiquidBottomTabs] 通过 [LocalLiquidBottomTabScale] 下发 ——
 * 之所以用 CompositionLocal 而不是直接把进度传进来，是因为
 * **内外两层内容必须共享同一个缩放值**（② 层的折射要跟着 ① 层一起胀），
 * 而这两层的 `content` 是同一个 lambda，逐参数传递做不到。
 *
 * ## 为什么点击区要 clip 成胶囊
 *
 * ① 底板的 `padding(4.dp)` 在 Row 内部，所以每个槽位的点击区本来会一直铺到
 * 底板的**直角边缘**上 —— 那 4dp 是圆角过渡区，按下去就落到玻璃外面了。
 * 裁成胶囊后，槽位边界跟着底板的圆角走，点哪都在玻璃上。
 *
 * ## 为什么关掉涟漪
 *
 * 玻璃的按压反馈由 [InteractiveHighlight] 的光斑 + 滑块的折射负责。
 * 再加一层 Material 涟漪会同时出现两种按压语言，互相打架
 * （而且是方角涟漪，跟整体的圆形语言不搭）。
 */
@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/**
 * 当前槽位内容的缩放倍数。
 *
 * `staticCompositionLocalOf` 而非 `compositionLocalOf`：这个值在
 * 一次组合内是稳定的（每次按压只改一次函数引用），用 static 版能避免
 * 无谓的细粒度重组开销。
 */
private val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }
