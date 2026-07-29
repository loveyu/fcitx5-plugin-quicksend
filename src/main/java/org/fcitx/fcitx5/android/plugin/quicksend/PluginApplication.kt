package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Application
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog

class PluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickSendManager.init(this)
        // 通用日志：初始化（解析文件、写进程启动标记）并安装崩溃捕获钩子
        VoiceLog.init(this)
        AppLog.installCrashHandler()
        // 更新后进程重建时自动恢复已启用的悬浮发送按钮
        OverlayRestarter.startIfEnabled(this)
    }
}
