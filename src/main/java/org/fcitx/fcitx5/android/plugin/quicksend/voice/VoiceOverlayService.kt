/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames
import java.io.File

/**
 * 语音输入浮层服务。由主程序点语音按钮后 `startForegroundService(START)` 启动：
 * 绑定主项目 [IQuickSendService] → 校验录音权限与模型就绪 → 弹出浮层并驱动 [VoiceController]
 * 进行本地流式识别（partial 进输入框组合区，完成提交）。前台服务 + microphone 类型满足
 * Android 14 后台录音要求。导出 + 签名 permission.PLUGIN 保护，仅同签名主程序可启动。
 */
class VoiceOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fcitxAppId get() = BuildConfig.FCITX_APP_ID

    private var windowManager: android.view.WindowManager? = null
    private var overlayView: View? = null
    private var partialText: TextView? = null
    private var statusText: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var promptView: TextView? = null
    private var pauseBtn: TextView? = null
    private var backspaceBtn: TextView? = null
    private var finishBtn: TextView? = null

    private var controller: VoiceController? = null
    private var remoteService: IQuickSendService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            VoiceLog.i(TAG, "bound to fcitx IQuickSendService")
            remoteService = IQuickSendService.Stub.asInterface(service)
            evaluateAndStart()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            VoiceLog.w(TAG, "fcitx IQuickSendService disconnected")
            remoteService = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VoiceLog.i(TAG, "onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VoiceLog.i(TAG, "onStartCommand action=${intent?.action}")
        startForegroundCompat()
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 绑定主项目 IQuickSendService（action 与 QuickSendOverlayService 一致）
        if (!bound) {
            bound = runCatching {
                bindService(
                    Intent("$fcitxAppId.quicksend.IPC").setPackage(fcitxAppId),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            VoiceLog.i(TAG, "bindService result=$bound (target=$fcitxAppId)")
        }
        showOverlay()
        evaluateAndStart()
        return START_NOT_STICKY
    }

    private fun evaluateAndStart() {
        if (overlayView == null) return
        if (controller != null) return
        when {
            !hasRecordAudio() -> {
                VoiceLog.w(TAG, "evaluate: RECORD_AUDIO not granted")
                showPrompt(getString(R.string.voice_need_record_permission))
            }
            !VoiceModelManager.isReady(this) -> {
                VoiceLog.w(TAG, "evaluate: model not ready")
                showPrompt(getString(R.string.voice_model_not_ready))
            }
            else -> {
                VoiceLog.i(TAG, "evaluate: ok, starting voice")
                startVoice()
            }
        }
    }

    private fun startVoice() {
        promptView?.visibility = View.GONE
        buttonRow?.visibility = View.VISIBLE
        val dir = File(getExternalFilesDir(null) ?: filesDir, VoiceModelManager.MODEL_DIR_NAME)
        val prefs = getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE)
        val recConfig = RecognitionConfig(
            decodingMethod = prefs.getString(QuickSendPrefs.VOICE_DECODING_METHOD, RecognitionConfig.DEFAULT_DECODING_METHOD) ?: RecognitionConfig.DEFAULT_DECODING_METHOD,
            maxActivePaths = prefs.getInt(QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS, RecognitionConfig.DEFAULT_MAX_ACTIVE_PATHS),
            blankPenalty = prefs.getFloat(QuickSendPrefs.VOICE_BLANK_PENALTY, RecognitionConfig.DEFAULT_BLANK_PENALTY),
            endpointSilence = prefs.getFloat(QuickSendPrefs.VOICE_ENDPOINT_SILENCE, RecognitionConfig.DEFAULT_ENDPOINT_SILENCE),
            endpointMaxUtterance = prefs.getFloat(QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER, RecognitionConfig.DEFAULT_ENDPOINT_MAX_UTTERANCE),
            numThreads = prefs.getInt(QuickSendPrefs.VOICE_NUM_THREADS, RecognitionConfig.DEFAULT_NUM_THREADS),
            provider = prefs.getString(QuickSendPrefs.VOICE_PROVIDER, RecognitionConfig.DEFAULT_PROVIDER) ?: RecognitionConfig.DEFAULT_PROVIDER
        )
        val ctrl = VoiceController(
            context = this,
            modelDir = dir,
            remote = { remoteService },
            names = SherpaModelNames(),
            config = recConfig,
            onSessionEnd = { mainHandler.post { stopSelf() } }
        )
        controller = ctrl
        scope.launch {
            ctrl.state.collect { runOnUiThread { updateUi(it) } }
        }
        ctrl.start()
    }

    private fun updateUi(state: VoiceUiState) {
        val pt = partialText ?: return
        val st = statusText ?: return
        when (state) {
            VoiceUiState.Idle -> { /* 会话结束，服务将 stopSelf */ }
            VoiceUiState.Initializing -> {
                pt.text = ""
                st.text = getString(R.string.voice_initializing)
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            VoiceUiState.Listening -> {
                st.text = getString(R.string.voice_listening)
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            is VoiceUiState.Partial -> {
                pt.text = state.text
                st.text = getString(R.string.voice_listening)
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            is VoiceUiState.Paused -> {
                pt.text = state.text
                st.text = getString(R.string.voice_paused)
                pauseBtn?.text = getString(R.string.voice_resume)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            VoiceUiState.Finishing -> st.text = getString(R.string.voice_committing)
            is VoiceUiState.Error -> st.text = state.message
            VoiceUiState.NotReady -> showPrompt(getString(R.string.voice_model_not_ready))
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return

        val title = TextView(this).apply {
            text = getString(R.string.voice_overlay_title)
            setTextColor(resolveColor(R.color.qs_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(resolveColor(R.color.qs_overlay_close))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(6), dp(10), dp(6))
            setOnClickListener { closeAndStop() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(2), dp(6))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
            addView(closeBtn)
        }

        val pt = TextView(this).apply {
            text = ""
            setTextColor(resolveColor(R.color.qs_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setMinHeight(dp(40))
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        val st = TextView(this).apply {
            text = getString(R.string.voice_listening)
            setTextColor(resolveColor(R.color.qs_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(0), dp(14), dp(8))
        }
        partialText = pt
        statusText = st

        val prompt = TextView(this).apply {
            visibility = View.GONE
            setTextColor(resolveColor(R.color.qs_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        promptView = prompt

        val stopBtn = makeButton(getString(R.string.voice_pause), secondary = true) {
            if (controller?.state?.value is VoiceUiState.Paused) {
                controller?.start()
            } else {
                controller?.pause()
            }
        }
        val delBtn = makeButton(getString(R.string.voice_backspace), secondary = true) {
            controller?.backspace()
        }
        val finishBtnView = makeButton(getString(R.string.voice_finish), secondary = false) {
            controller?.finish()
        }
        pauseBtn = stopBtn
        backspaceBtn = delBtn
        finishBtn = finishBtnView
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(10), dp(4), dp(10), dp(10))
            addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(delBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(finishBtnView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        buttonRow = row

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(resolveColor(R.color.qs_surface))
                cornerRadius = dp(12).toFloat()
                setStroke(1, resolveColor(R.color.qs_divider))
            }
            elevation = dp(6).toFloat()
            addView(header)
            addView(
                View(this@VoiceOverlayService).apply { setBackgroundColor(resolveColor(R.color.qs_overlay_divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
            addView(prompt)
            addView(pt)
            addView(st)
            addView(row)
        }

        val lp = android.view.WindowManager.LayoutParams(
            dp(300),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }
        runCatching { wm.addView(container, lp) }
        overlayView = container
    }

    private fun makeButton(text: String, secondary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val p = dp(12)
            setPadding(p, dp(10), p, dp(10))
            if (secondary) {
                setTextColor(resolveColor(R.color.qs_text_primary))
                background = GradientDrawable().apply {
                    setColor(resolveColor(R.color.qs_overlay_divider))
                    cornerRadius = dp(8).toFloat()
                }
            } else {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(resolveColor(R.color.qs_accent_overlay_bg))
                    cornerRadius = dp(8).toFloat()
                }
            }
            setOnClickListener { onClick() }
        }

    private fun showPrompt(message: String) {
        promptView?.apply {
            text = message
            visibility = View.VISIBLE
        }
        buttonRow?.visibility = View.GONE
        // 提示时把按钮区改为单"去设置"
        buttonRow?.removeAllViews()
        buttonRow?.addView(
            makeButton(getString(R.string.voice_open_settings), secondary = false) { openSettings() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        buttonRow?.visibility = View.VISIBLE
        partialText?.text = ""
        statusText?.text = ""
    }

    private fun closeAndStop() {
        runCatching { controller?.close() }
        stopSelf()
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, VoiceSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        stopSelf()
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        partialText = null
        statusText = null
        buttonRow = null
        promptView = null
        pauseBtn = null
        backspaceBtn = null
        finishBtn = null
    }

    override fun onDestroy() {
        VoiceLog.i(TAG, "onDestroy")
        controller?.destroy()
        controller = null
        removeOverlay()
        runCatching { if (bound) { unbindService(connection); bound = false } }
        remoteService = null
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.voice_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_mic)
            .setContentTitle(getString(R.string.voice_notif_title))
            .setContentText(getString(R.string.voice_notif_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun resolveColor(id: Int): Int = ContextCompat.getColor(this, id)

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    companion object {
        const val ACTION_START = "org.fcitx.fcitx5.android.plugin.quicksend.voice.START"
        private const val CHANNEL_ID = "voice_input"
        private const val NOTIF_ID = 0x7e01
        private const val TAG = "VoiceOverlay"
    }
}
