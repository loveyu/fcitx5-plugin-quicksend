/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.content.Context
import org.fcitx.fcitx5.android.plugin.quicksend.AppLog
import java.io.File

/**
 * 语音子系统日志门面：委托给插件级通用日志 [AppLog]（默认 WARN 级、可开 DEBUG、
 * 同步捕获崩溃、支持清空/分享）。保留既有 `d/i/w/e` 与 `path/file` 入口，
 * 因此现有调用点无需改动；新的非语音代码请直接使用 [AppLog]。
 */
object VoiceLog {

    fun init(context: Context) = AppLog.init(context)

    fun path(context: Context): String = AppLog.path(context)

    fun file(context: Context): File? = AppLog.file(context)

    fun d(tag: String, msg: String) = AppLog.d(tag, msg)

    fun i(tag: String, msg: String) = AppLog.i(tag, msg)

    fun w(tag: String, msg: String, t: Throwable? = null) = AppLog.w(tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = AppLog.e(tag, msg, t)
}
