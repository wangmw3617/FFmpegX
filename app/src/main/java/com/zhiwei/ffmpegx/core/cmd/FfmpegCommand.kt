package com.zhiwei.ffmpegx.core.cmd

import com.zhiwei.ffmpegx.core.hw.DecoderPlan
import com.zhiwei.ffmpegx.core.hw.EncoderPlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlan

/**
 * 极简 FFmpeg 命令行构造器。
 *
 * 刻意保持「顺序敏感」：FFmpeg 的选项位置决定作用域，
 * 全局选项必须在第一个 `-i` 之前，输出选项必须在最后一个 `-i` 之后。
 * 因此这里不做任何自动重排，调用方按顺序 append。
 */
class FfmpegCommand {

    private val args = mutableListOf<String>()

    /** 追加若干原始 token */
    fun raw(vararg values: String): FfmpegCommand = apply { args += values }

    fun raw(values: List<String>): FfmpegCommand = apply { args += values }

    /** 追加 `-name value` 形式；value 为 null 时跳过 */
    fun opt(name: String, value: Any?): FfmpegCommand = apply {
        if (value != null) {
            args += name
            args += value.toString()
        }
    }

    fun flag(name: String, enabled: Boolean = true): FfmpegCommand = apply {
        if (enabled) args += name
    }

    fun input(path: String, preArgs: List<String> = emptyList()): FfmpegCommand = apply {
        args += preArgs
        args += "-i"
        args += path
    }

    fun filterGraph(filters: List<String>): FfmpegCommand = apply {
        val merged = filters.filter { it.isNotBlank() }
        if (merged.isNotEmpty()) {
            args += "-vf"
            args += merged.joinToString(",")
        }
    }

    fun audioFilterGraph(filters: List<String>): FfmpegCommand = apply {
        val merged = filters.filter { it.isNotBlank() }
        if (merged.isNotEmpty()) {
            args += "-af"
            args += merged.joinToString(",")
        }
    }

    fun map(vararg specifiers: String): FfmpegCommand = apply {
        specifiers.forEach {
            args += "-map"
            args += it
        }
    }

    fun output(path: String, preArgs: List<String> = emptyList()): FfmpegCommand = apply {
        args += preArgs
        args += path
    }

    fun build(): List<String> = args.toList()

    /** 用于「命令预览」，只做展示不做转义（真实执行走 argv 数组，不经过 shell） */
    override fun toString(): String = build().joinToString(" ") { token ->
        if (token.any { it.isWhitespace() || it == '"' || it == '\'' }) "'$token'" else token
    }

    /**
     * 按硬件加速规划落视频编码器。
     *
     * @param copy 为 true 时直接 `-c:v copy`（无损直通）
     */
    fun applyVideoEncoder(
        plan: HardwarePlan,
        copy: Boolean,
        softwareFallbackName: String?,
        extraOutputArgs: List<String> = emptyList(),
    ): FfmpegCommand = apply {
        when {
            copy -> raw("-c:v", "copy")

            plan.encoder is EncoderPlan.MediaCodec -> {
                raw("-c:v", plan.encoder.ffmpegName)
                raw(plan.outputArgs)
            }

            plan.encoder is EncoderPlan.Software -> {
                raw("-c:v", plan.encoder.ffmpegName)
            }

            else -> {
                // 规划失败时兜底，绝不生成一条必然报错的命令
                val fallback = softwareFallbackName ?: "libx264"
                raw("-c:v", fallback)
            }
        }
        if (extraOutputArgs.isNotEmpty()) raw(extraOutputArgs)
    }

    /** 输入侧硬件解码参数 */
    fun applyDecoderArgs(plan: HardwarePlan): FfmpegCommand = apply {
        if (plan.decoder is DecoderPlan.MediaCodec) {
            raw(plan.inputArgs)
        }
    }

    companion object {
        fun create(): FfmpegCommand = FfmpegCommand()
    }
}
