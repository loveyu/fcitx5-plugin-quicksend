package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 悬浮发送按钮服务的自启动恢复。
 *
 * 问题：插件（APK）更新后系统会终止并清除 [QuickSendOverlayService] 的重启计划，
 * 而该服务仅由 PluginActivity 的开关启动 —— 于是更新后悬浮按钮不再出现，必须手动
 * 关闭再打开开关才能恢复。
 *
 * 修复：在插件进程被创建（[PluginApplication.onCreate]）与被输入法绑定
 * （[MainService.onCreate]）这两个时机，若用户已启用悬浮按钮且已获
 * SYSTEM_ALERT_WINDOW 权限，则自动 startService。startService 包 runCatching，
 * 即便处于后台启动限制场景也不会崩溃（最坏退化为原手动开关行为）。
 */
object OverlayRestarter {

    fun startIfEnabled(context: Context) {
        val prefs = context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(QuickSendPrefs.OVERLAY_ENABLED, false)) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            context.startService(Intent(context, QuickSendOverlayService::class.java))
        }
    }
}
