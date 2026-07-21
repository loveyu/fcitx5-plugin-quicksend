package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Application
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager

class PluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickSendManager.init(this)
    }
}
