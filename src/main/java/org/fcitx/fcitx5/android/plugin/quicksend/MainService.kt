package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService

private const val TAG = "QuickSendMainService"
private val FCITX_APP_ID get() = BuildConfig.FCITX_APP_ID

/**
 * 插件 Service：fcitx5-android 主程序会 bind 本 Service。
 *
 * 被 bind 时，本服务反向 bind 主程序的 [IQuickSendService]，并将代理存入
 * [RemoteServiceHolder]，供 [QuickSendExecutor] 发送按键 / 文本。
 *
 * 注意：双方需使用相同签名证书（signature 级 IPC 权限）。
 */
class MainService : Service() {

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            RemoteServiceHolder.service = IQuickSendService.Stub.asInterface(service)
            Log.d(TAG, "Connected to fcitx5-android")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            RemoteServiceHolder.service = null
            Log.d(TAG, "Disconnected from fcitx5-android")
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Bound by fcitx5-android")
        bindService(
            Intent("$FCITX_APP_ID.quicksend.IPC").setPackage(FCITX_APP_ID),
            connection,
            Context.BIND_AUTO_CREATE
        )
        return Messenger(Handler(Looper.getMainLooper())).binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { unbindService(connection) }
        RemoteServiceHolder.service = null
        Log.d(TAG, "Unbound from fcitx5-android")
        return false
    }
}
