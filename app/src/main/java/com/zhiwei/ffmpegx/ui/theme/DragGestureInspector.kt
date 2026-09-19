package com.zhiwei.ffmpegx.ui.theme

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull

/**
 * 一套「既能拖、也能点」的手势识别器。
 *
 * 取自上游 AndroidLiquidGlass 的 `catalog/utils/DragGestureInspector.kt`，
 * 原样移植（只改了包名与注释）。
 *
 * ## 为什么不用现成的 `detectDragGestures`
 *
 * 底栏要同时支持两种操作：轻点切换标签、按住横向拖动滑块。
 * 用 Compose 自带的 `detectDragGestures` 会有两个问题：
 *
 * 1. 它**等触摸超过滑动阈值才触发** `onDragStart`，所以「按下」这个瞬间
 *    拿不到回调 —— 而液态玻璃的按压缩放、高光、折射增强全都要在
 *    **按下的那一刻**就开始动，晚了就显得木。
 * 2. `detectTapGestures` 与 `detectDragGestures` 是两个独立识别器，
 *    叠在一起会争抢事件，出现「点了没反应」或「拖了又触发点击」。
 *
 * 这里自己写循环：**一按下就回调 `onDragStart`**，随后每次位置变化都回调
 * `onDrag`（包括位移为 0 的那一次），抬起/取消则分别回调
 * `onDragEnd` / `onDragCancel`。点击与拖动由调用方按位移自行判断，
 * 不需要两个识别器互相竞争。
 *
 * ## 多指处理
 *
 * 一旦当前指针抬起但还有其他手指按着（`otherDown != null`），
 * 就把跟踪目标切到那只手上继续 —— 这样两指交替拖也能连贯跟手，
 * 而不是第一根手指一松就断掉。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        // Initial 阶段先探一下，保证在别人消费事件前就拿到按下点
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = drag(pointerId = down.id, onDrag = { onDrag(it, it.positionChange()) })
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

/**
 * 跟踪某个指针直到抬起，期间把每次位移交给 [onDrag]。返回抬起事件；中途丢失则返回 null。
 *
 * ⚠️ **这里刻意不加 `inline`**（上游那份是 `private suspend inline fun`）。
 * `AwaitPointerEventScope` 的挂起成员/扩展函数是 Compose 的**受限挂起函数**，
 * `inline` 会让编译器用「受限协程作用域」规则去校验调用点，而调用点位于
 * `awaitEachGesture { }` 内部 —— 两个隐式接收者叠加时容易触发
 * 「Restricted suspending functions can invoke member or extension suspending
 * functions only on their restricted coroutine scope」。
 * 去掉 `inline` 后按普通挂起函数处理，规则简单且行为一致；
 * 这点性能开销（一次非内联调用）在每帧最多几次的拖动手势里可以忽略。
 */
private suspend fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null

    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        // 被别的组件消费掉了就放弃 —— 比如中途划到了可滚动的父容器
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

/**
 * 等一个「发生位移」或「抬起」的事件。
 *
 * 同样刻意不加 `inline`，理由见 [drag] 上方。
 */
private suspend fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                // 还有别的手指按着，接力继续跟它
                pointer = otherDown.id
            }
        } else {
            if (dragEvent.previousPosition != dragEvent.position) {
                return dragEvent
            }
        }
    }
}
