package com.zhiwei.ffmpegx.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 承接来自系统「分享 / 用其他应用打开」的输入文件。
 *
 * Activity 收到 intent 后把 Uri 放进来，工作台页在首次组合时消费一次。
 * 用单槽而不是队列：用户分享文件时通常只期望处理最近的那一个。
 */
object SharedInput {

    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    fun post(uri: Uri) {
        _pending.value = uri
    }

    fun consume(): Uri? {
        val current = _pending.value
        _pending.value = null
        return current
    }

    /** 从 SEND / VIEW intent 里提取第一个可用的媒体 Uri */
    fun extract(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}
