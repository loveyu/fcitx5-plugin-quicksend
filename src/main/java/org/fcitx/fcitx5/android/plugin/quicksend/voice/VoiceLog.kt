/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 语音输入调试日志：写入应用专用外部目录（便于用户用文件管理器/adb 复制）。
 *
 * 路径：`getExternalFilesDir(null)/logs/voice.log`（不可得时回落 filesDir）。
 * 单线程追加写，超 2MB 滚动到 `.old`（保留 1 份历史）。所有写入异步，不阻塞调用方。
 *
 * 语音处于调试阶段，关键节点（生命周期、IPC、识别事件、下载、错误）均落盘，
 * 与 logcat 互为补充，便于用户上报问题。
 */
object VoiceLog {

    private const val TAG = "VoiceLog"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_NAME = "voice.log"
    private const val DIR_NAME = "logs"

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "voice-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = resolveFile(context)
    }

    /** 日志文件绝对路径（用于设置页展示）。 */
    fun path(context: Context): String = resolveFile(context).absolutePath

    /** 日志文件（用于分享）；不存在则返回 null。 */
    fun file(context: Context): File? =
        resolveFile(context).takeIf { it.exists() && it.length() > 0 }

    private fun resolveFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, DIR_NAME).apply { mkdirs() }, FILE_NAME)
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val f = file ?: return
        val trace = t?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
        val line = "${fmt.format(Date())} $level/$tag: $msg$trace\n"
        executor.execute {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    File(f.parentFile, "$FILE_NAME.old").let { old ->
                        if (old.exists()) old.delete()
                        f.renameTo(old)
                    }
                }
                FileOutputStream(f, true).use { it.write(line.toByteArray()) }
            } catch (_: Throwable) {
                // 日志失败不应影响语音主流程
            }
        }
    }
}
