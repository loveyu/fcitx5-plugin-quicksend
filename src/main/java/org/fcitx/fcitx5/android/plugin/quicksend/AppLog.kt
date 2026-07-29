/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 插件级通用日志（不再仅限语音子系统）。
 *
 * - 默认 WARN 级别：W/E 同时写入文件与 logcat；开启「调试日志」后追加 D/I。
 * - 单线程追加写，超 2MB 滚动到 `.old`（保留 1 份历史）；写入异步，不阻塞调用方。
 * - [installCrashHandler] 捕获未处理异常，崩溃栈同步写入日志后继续上抛默认行为，
 *   确保进程被杀也留痕；[init] 写入进程启动标记，便于在日志中定位崩溃前后边界。
 *
 * 路径：`getExternalFilesDir(null)/logs/app.log`（不可得时回落 filesDir）。
 *
 * 既有语音代码经 [org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog] 门面委托到此处。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_NAME = "app.log"
    private const val DIR_NAME = "logs"

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var debugEnabled = false

    fun init(context: Context) {
        file = resolveFile(context)
        debugEnabled = context
            .getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
            .getBoolean(QuickSendPrefs.LOG_DEBUG_ENABLED, false)
        // 进程启动标记始终记录，便于在日志中定位崩溃/会话边界
        writeAsyncRaw(
            "I/$TAG: ----- process started " +
                "(pid=${android.os.Process.myPid()}, debug=$debugEnabled) -----"
        )
    }

    fun setDebugEnabled(context: Context, enabled: Boolean) {
        debugEnabled = enabled
        context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(QuickSendPrefs.LOG_DEBUG_ENABLED, enabled).apply()
        writeAsyncRaw("I/$TAG: debug logging ${if (enabled) "enabled" else "disabled"}")
    }

    fun isDebugEnabled(): Boolean = debugEnabled

    /** 日志文件绝对路径（用于设置页展示）。 */
    fun path(context: Context): String = resolveFile(context).absolutePath

    /** 日志文件（用于分享）；不存在或为空则返回 null。 */
    fun file(context: Context): File? =
        resolveFile(context).takeIf { it.exists() && it.length() > 0 }

    /** 清空日志（当前文件 + 滚动历史），并留一条操作记录。 */
    fun clear(context: Context) {
        val f = resolveFile(context)
        executor.execute {
            runCatching {
                File(f.parentFile, "$FILE_NAME.old").delete()
                f.delete()
            }
        }
        writeAsyncRaw("I/$TAG: log cleared")
    }

    fun d(tag: String, msg: String) = log("D", tag, msg, null) { Log.d(tag, msg) }
    fun i(tag: String, msg: String) = log("I", tag, msg, null) { Log.i(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }

    /** 安装未捕获异常钩子：同步写入崩溃栈，再交还原默认处理（让进程按正常流程终止）。 */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val banner = "\n${fmt.format(Date())} E/$TAG: " +
                    "===== FATAL: uncaught exception on ${thread.name} =====\n" +
                    Log.getStackTraceString(throwable) + "\n"
                writeSyncRaw(banner)
            } catch (_: Throwable) {
                // 崩溃钩子里绝不能再抛
            }
            previous.uncaughtException(thread, throwable)
        }
    }

    private inline fun log(
        level: String,
        tag: String,
        msg: String,
        t: Throwable?,
        logcat: () -> Unit
    ) {
        // 未开启调试时，D/I 既不落盘也不进 logcat
        if (!debugEnabled && (level == "D" || level == "I")) return
        logcat()
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
                // 日志失败不应影响主流程
            }
        }
    }

    /** 异步写一行（绕过级别过滤），用于启动/清空等关键标记。 */
    private fun writeAsyncRaw(line: String) {
        val f = file ?: return
        val stamped = "${fmt.format(Date())} $line\n"
        executor.execute {
            try {
                FileOutputStream(f, true).use { it.write(stamped.toByteArray()) }
            } catch (_: Throwable) {
            }
        }
    }

    /** 同步写一行（绕过级别过滤 + 绕过队列），用于崩溃钩子，保证进程退出前落盘。 */
    private fun writeSyncRaw(line: String) {
        val f = file ?: return
        try {
            FileOutputStream(f, true).use { it.write(line.toByteArray()) }
        } catch (_: Throwable) {
        }
    }

    private fun resolveFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, DIR_NAME).apply { mkdirs() }, FILE_NAME)
    }
}
