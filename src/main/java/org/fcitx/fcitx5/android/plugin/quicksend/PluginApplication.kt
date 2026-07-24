package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Application
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog

class PluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickSendManager.init(this)
        VoiceLog.init(this)
        // 更新后进程重建时自动恢复已启用的悬浮发送按钮
        OverlayRestarter.startIfEnabled(this)
    }
}
