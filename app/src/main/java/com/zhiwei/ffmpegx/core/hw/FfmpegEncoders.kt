package com.zhiwei.ffmpegx.core.hw

import android.util.Log
import com.zhiwei.ffmpegx.native.FFmpegNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「当前 FFmpeg 构建里到底有哪些编码器」的运行时缓存。
 *
 * ## 为什么必须单独问一次
 *
 * **设备支持 MediaCodec 编码 ≠ FFmpeg 里有对应的编码器。**
 *
 * 前者由 Android 的 `MediaCodecList` 决定（[MediaCodecScanner] 读的就是它）；
 * 后者完全取决于 AAR 构建时有没有加 `--enable-lib-android-media-codec`
 * —— 这个选项在 ffmpeg-kit-next 里**默认是关闭的**。
 *
 * 两者不一致时，按设备能力生成的 `-c:v h264_mediacodec` 会在 FFmpeg 启动瞬间失败：
 *
 * ```
 * FFmpeg 退出码 1: Error opening output files: Encoder not found
 * ```
 *
 * 而且失败得极快（十几毫秒），因为编码器在打开输出流时就被解析了。
 *
 * 所以规划前先问一次「FFmpeg 自己有什么」，是唯一可靠的做法。
 */
object FfmpegEncoders {

    private const val TAG = "FfmpegEncoders"

    /** null 表示尚未探测；空集表示探测过但没拿到（按「不做限制」处理） */
    @Volatile
    private var cache: Set<String>? = null

    /** 探测是否已完成。UI 可据此决定要不要提示「硬件加速不可用」 */
    val isProbed: Boolean get() = cache != null

    /**
     * 探测并缓存可用编码器。可重复调用，只会真正执行一次探测。
     *
     * 内部已切到 IO 线程，但仍建议在应用启动时用协程调用，不要卡主线程。
     */
    suspend fun probe(): Set<String> {
        cache?.let { return it }
        val found = withContext(Dispatchers.IO) {
            runCatching { FFmpegNative.listEncoders() }
                .onFailure { Log.w(TAG, "编码器探测失败，按「不限制」处理", it) }
                .getOrDefault(emptySet())
        }
        if (found.isNotEmpty()) {
            cache = found
            Log.i(TAG, "FFmpeg 构建包含 ${found.size} 个编码器")
        } else {
            Log.w(TAG, "未能取到编码器列表，后续不做可用性过滤")
        }
        return found
    }

    /**
     * 当前已知的编码器集合快照。尚未探测时返回空集。
     *
     * 空集在 [HardwarePlanCalculator] 里被解释为「未知，不做限制」，
     * 这样探测失败也不会把硬件加速功能整个挡掉。
     */
    fun snapshot(): Set<String> = cache.orEmpty()

    /** 仅供单元测试重置状态 */
    fun resetForTest() {
        cache = null
    }
}
