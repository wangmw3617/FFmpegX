package com.zhiwei.ffmpegx.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 一套带动量与阻尼的「跟手拖拽 + 吸附落位」动画状态机。
 *
 * 取自上游 AndroidLiquidGlass 的 `catalog/utils/DampedDragAnimation.kt`，按本项目改了两处：
 *
 * 1. **去掉 `kotlin.time.Clock`**，改用 `System.nanoTime()`。
 *    上游用 `Clock.System.now().toEpochMilliseconds()` 给速度追踪器打时间戳，
 *    而 `kotlin.time.Clock` 在 Kotlin 2.3 上仍是实验性 API，会带一层
 *    `@OptIn(ExperimentalTime::class)`。速度追踪只关心**时间差**，
 *    `System.nanoTime()` 单调且够精确，还省掉注解。
 * 2. 包名换成本项目的，注释改成中文。
 *
 * 注：`awaitFrame()` 来自 `kotlinx.coroutines.android`（即 `kotlinx-coroutines-android`
 * 给协程补的「等下一帧」原语），上游那个文件里漏写了这条 import。
 * 项目本来就直接依赖该库，无需额外声明。
 *
 * ## 它解决什么问题
 *
 * 单纯用 `animateFloatAsState` 做指示器，只能「点一下、滑过去」。
 * 这个类让滑块像实体一样有惯性与阻尼：
 *
 * - **按下**：`press()` 让整体轻微放大（`pressedScale`），并驱动
 *   `pressProgress` 从 0 涨到 1 —— 玻璃的折射、高光都读这个值做插值。
 * - **拖动**：`updateValue()` 边走边算速度（`velocity`），
 *   松开时用速度决定落点手感；`scaleX`/`scaleY` 会按速度做轻微拉伸，
 *   就是那种「甩出去」的形变。
 * - **落位**：`release()` 会**等值先追上目标再收回缩放**，
 *   否则会出现「还没到位就缩小」的割裂感。
 *
 * ## 为什么所有状态都用 Animatable 而不是普通 Float
 *
 * 因为要能**被打断**。用户按住不放时，正在跑的吸附动画必须能被新的拖动
 * 直接接管，`Animatable.animateTo` 天然支持这种抢占；换成 `mutableStateOf`
 * 就得自己处理动画取消，很容易出现状态错乱。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    /** 位置动画：阻尼 1.0 偏「紧」，跟手时不会晃 */
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)

    /** 速度回零：阻尼 0.5 让它有回弹感，但比位置动画快收敛 */
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)

    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)

    /** X 轴比 Y 轴更「软」，横向甩动时形变更明显，符合直觉 */
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    /** 保证同一时刻只有一个「吸附落位」在跑，避免连续切换标签时多个动画打架 */
    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value

    /** 归一化进度（0 在首项、1 在末项）*/
    val progress: Float
        get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)

    val targetValue: Float get() = valueAnimation.targetValue

    /** 按压进度：0 = 完全松开，1 = 完全按下。玻璃的折射/高光都插值读它 */
    val pressProgress: Float get() = pressProgressAnimation.value

    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    /** 当前速度，用于出口处的拉伸形变 */
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    /** 按下：放大 + 压进感拉满 */
    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    /**
     * 松开。
     *
     * 关键在**先等值追到目标附近**（阈值为整个量程的 2.5%）再收回缩放：
     * 如果一松手就立刻缩小，会看到「滑块还在滑、但已经缩回去了」的错位。
     */
    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /** 拖动中更新位置。会被夹在 [valueRange] 内，防止滑出底栏。 */
    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    /**
     * 吸附到某一格。
     *
     * 整段用 [mutatorMutex] 保护：吸附过程中若用户又按下去，新的拖动会
     * 抢占它，而不是两个动画同时改 `valueAnimation` 导致抖动。
     */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    /**
     * 按位移采样速度。
     *
     * 把「位置」当作一维坐标喂给 [VelocityTracker]，这样能复用 Compose 现成的
     * 速度估算（它内部会做时间窗与加权）。除以量程是为了归一化 ——
     * 后面 scaleX/scaleY 的形变系数是按「每秒跳过几格」来理解的，
     * 不归一化的话，标签数量一变手感就全变了。
     */
    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.nanoTime() / 1_000_000L,
            Offset(value, 0f),
        )
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
