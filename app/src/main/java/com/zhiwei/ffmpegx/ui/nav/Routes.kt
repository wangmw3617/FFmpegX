package com.zhiwei.ffmpegx.ui.nav

import kotlinx.serialization.Serializable

/** 底部导航的四个一级页面 */
@Serializable
object HomeRoute

@Serializable
object QueueRoute

@Serializable
object ConsoleRoute

@Serializable
object SettingsRoute

/** 工具页。每个工具是独立路由，进入后各自持有一份工作台状态。 */
@Serializable
object ConvertRoute

@Serializable
object CompressRoute

@Serializable
object TrimRoute

@Serializable
object AudioRoute

@Serializable
object GifRoute

@Serializable
object ConcatRoute

@Serializable
object SubtitleRoute

@Serializable
object OverlayRoute

@Serializable
object ProbeRoute

/** 原始命令行工具（区别于底部导航的「命令」日志页） */
@Serializable
object RawCommandRoute
