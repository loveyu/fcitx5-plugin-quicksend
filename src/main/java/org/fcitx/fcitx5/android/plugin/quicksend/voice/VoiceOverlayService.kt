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
import android.content.SharedPreferences
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
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.common.ipc.IInputWindowStateListener
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService
import org.fcitx.fcitx5.android.plugin.quicksend.AppLog
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackend
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.RemoteBackendStore
import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.recognizer
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelHolder
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaRecognizer
import java.io.File

class VoiceOverlayService : Service() {

    private val crashHandler = CoroutineExceptionHandler { _, t ->
        // 任何协程未捕获异常（网络/识别/回退等）在此捕获：记日志 + 主线程 Toast，
        // 避免进程崩溃导致整个语音 UI 挂掉；用户可再次点麦克风重试。
        AppLog.e(TAG, "uncaught coroutine exception", t)
        mainHandler.post {
            Toast.makeText(this, getString(R.string.voice_crash_toast), Toast.LENGTH_LONG).show()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashHandler)
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
    private var forceLocalToggle: TextView? = null

    private var controller: VoiceController? = null
    private var collectJob: Job? = null
    private var remoteService: IQuickSendService? = null
    private var bound = false
    private var registered = false

    private var voiceMode = VoiceMode.LOCAL
    private var currentPrefs: SharedPreferences? = null

    /** 远端后端优先级链（仅 enable && tested），[queueIndex] 指向当前正在尝试的后端。 */
    private var remoteQueue: List<RemoteBackend> = emptyList()
    private var queueIndex = -1

    /** 单后端测试模式：从设置页拉起，[testBackend] 之外的判断跳过，final 回写 tested。 */
    private var inTestMode = false
    private var testBackend: RemoteBackend? = null

    private val backendJson = Json { ignoreUnknownKeys = true }

    private enum class VoiceMode { LOCAL, REMOTE, REMOTE_FALLBACK_LOCAL }

    private val inputWindowListener = object : IInputWindowStateListener.Stub() {
        override fun onInputWindowShown() {}

        override fun onInputWindowHidden() {
            if (inTestMode) return // 测试模式：不因输入窗隐藏而关闭测试浮层
            VoiceLog.i(TAG, "input window hidden, closing voice overlay")
            mainHandler.post { closeAndStop() }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            VoiceLog.i(TAG, "bound to fcitx IQuickSendService")
            val s = IQuickSendService.Stub.asInterface(service)
            remoteService = s
            runCatching {
                s.registerInputWindowStateListener(inputWindowListener)
                registered = true
            }
            evaluateAndStart()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            VoiceLog.w(TAG, "fcitx IQuickSendService disconnected")
            registered = false
            remoteService = null
            mainHandler.post { closeAndStop() }
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
        // 单后端测试模式：携带待测后端 JSON。设置页点「测试」时拉起。
        if (intent.getBooleanExtra(EXTRA_TEST_MODE, false)) {
            val json = intent.getStringExtra(EXTRA_TEST_BACKEND_JSON)
            testBackend = json?.let { runCatching { backendJson.decodeFromString<RemoteBackend>(it) }.getOrNull() }
            inTestMode = testBackend != null
            if (!inTestMode) {
                VoiceLog.w(TAG, "test mode requested but backend json missing/invalid")
                stopSelf()
                return START_NOT_STICKY
            }
            VoiceLog.i(TAG, "test mode for backend id=${testBackend?.id}")
        }
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
        if (!hasRecordAudio()) {
            VoiceLog.w(TAG, "evaluate: RECORD_AUDIO not granted")
            showPrompt(getString(R.string.voice_need_record_permission))
            return
        }
        val prefs = getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE)
        currentPrefs = prefs

        // 测试模式：只跑指定后端，跳过「启用/模型就绪」判断，final 回写 tested。
        if (inTestMode) {
            val tb = testBackend
            if (tb == null) {
                stopSelf()
                return
            }
            VoiceLog.i(TAG, "evaluate: test mode, backend=${tb.name}(${tb.id})")
            remoteQueue = listOf(tb)
            queueIndex = 0
            voiceMode = VoiceMode.REMOTE
            promptView?.visibility = View.GONE
            buttonRow?.visibility = View.VISIBLE
            runOnUiThread { updateUi(VoiceUiState.Initializing) }
            createAndStartController(onFinal = { text -> onTestFinal(text) }) { tb.recognizer() }
            return
        }

        // 正常模式：构建优先级链（enable && tested）。链空且本地模型未就绪 → 阻塞。
        remoteQueue = RemoteBackendStore.activeBackends(this)
        if (remoteQueue.isEmpty() && !VoiceModelManager.isReady(this)) {
            VoiceLog.w(TAG, "evaluate: no active remote and model not ready")
            showPrompt(getString(R.string.voice_model_not_ready))
            return
        }
        VoiceLog.i(TAG, "evaluate: ok, remote chain size=${remoteQueue.size}")
        startVoice(prefs)
    }

    private fun startVoice(prefs: android.content.SharedPreferences) {
        promptView?.visibility = View.GONE
        buttonRow?.visibility = View.VISIBLE
        if (remoteQueue.isNotEmpty()) {
            // 有启用的远端 → 从队首进入 REMOTE；会话内强制本地/回退由切换函数处理。
            voiceMode = VoiceMode.REMOTE
            queueIndex = 0
            updateForceLocalToggle()
            launchRemoteBackend(remoteQueue[0])
        } else {
            // 无远端 → 直接本地
            voiceMode = VoiceMode.LOCAL
            updateForceLocalToggle()
            VoiceLog.i(TAG, "no active remote, using local Sherpa")
            createAndStartController { makeLocalRecognizer(prefs) }
        }
    }

    /** 按 [remoteQueue] 的第 [queueIndex] 个后端创建并启动识别器。 */
    private fun launchRemoteBackend(backend: RemoteBackend) {
        VoiceLog.i(TAG, "using remote backend #${queueIndex + 1}/${remoteQueue.size}: ${backend.name}(${backend.id})")
        createAndStartController { backend.recognizer() }
    }

    /**
     * 尝试链中下一个后端（当前后端失败、但链未耗尽时）。teardown 后立即显示「初始化中」，
     * 避免状态冻结（与切本地对称的真空期处理，见 tech-debt #2）。
     */
    private fun switchToNextRemote() {
        val next = queueIndex + 1
        if (next >= remoteQueue.size) return
        VoiceLog.i(TAG, "switch to next remote #$next")
        queueIndex = next
        teardownCurrentController()
        runOnUiThread { updateUi(VoiceUiState.Initializing) }
        launchRemoteBackend(remoteQueue[next])
    }

    /**
     * 切换到本地模型。[displayMode] 决定前缀显示：手动切换为 [VoiceMode.LOCAL]（[L]），
     * 远端失败自动回退为 [VoiceMode.REMOTE_FALLBACK_LOCAL]（[NL]，红色 N 提示）。
     * 显式预加载模型：失败时 Toast 提示并关闭，避免裸异常。
     */
    private fun switchToLocalMode(displayMode: VoiceMode, reason: String) {
        val prefs = currentPrefs ?: return
        if (!VoiceModelManager.isReady(this)) {
            AppLog.e(TAG, "cannot switch to local, model not ready ($reason)")
            mainHandler.post {
                Toast.makeText(this, getString(R.string.voice_fallback_failed), Toast.LENGTH_LONG).show()
                closeAndStop()
            }
            return
        }
        VoiceLog.i(TAG, "switch to local: $reason (mode=$displayMode)")
        voiceMode = displayMode
        updateForceLocalToggle()
        teardownCurrentController()
        // 模型首次加载耗时数秒，此期间已无 controller 驱动 UI；手动刷新为本地「初始化中」，
        // 否则状态文本会冻结在切换前的「[N]正在聆听」，误导用户以为网络仍在识别
        // （此时网络已断、本地尚未就绪）。voiceMode 已改为 LOCAL/REMOTE_FALLBACK_LOCAL，
        // buildStatusText 据此生成 [L]/[NL] 前缀，随后新 controller 的 Listening 自然衔接。
        runOnUiThread { updateUi(VoiceUiState.Initializing) }
        scope.launch(Dispatchers.IO) {
            try {
                val rec = makeLocalRecognizer(prefs)
                runOnUiThread { createAndStartController { rec } }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                AppLog.e(TAG, "switch to local failed", t)
                mainHandler.post {
                    Toast.makeText(this@VoiceOverlayService, getString(R.string.voice_fallback_failed), Toast.LENGTH_LONG).show()
                    closeAndStop()
                }
            }
        }
    }

    /** 切回网络模式（用户点击强制本地开关关闭时）：从链首重入。 */
    private fun switchToRemoteMode() {
        if (remoteQueue.isEmpty()) {
            VoiceLog.w(TAG, "switch to remote: no active backend")
            showPrompt(getString(R.string.voice_no_active_remote))
            return
        }
        VoiceLog.i(TAG, "switch to remote (user toggled back), from head")
        voiceMode = VoiceMode.REMOTE
        queueIndex = 0
        updateForceLocalToggle()
        teardownCurrentController()
        // 与切本地对称：teardown 后到新 controller 接管前先显示「[N]初始化中」，
        // 避免冻结在切换前的本地状态文案；WebSocket 握手期间亦同。
        runOnUiThread { updateUi(VoiceUiState.Initializing) }
        launchRemoteBackend(remoteQueue[0])
    }

    /** 悬浮窗顶部的「强制本地」开关：仅在远端（网络）模式可用时显示，仅当前会话生效。 */
    private fun onForceLocalClicked() {
        if (voiceMode == VoiceMode.REMOTE) {
            switchToLocalMode(VoiceMode.LOCAL, "user toggle")
        } else {
            switchToRemoteMode()
        }
    }

    private fun teardownCurrentController() {
        collectJob?.cancel()
        collectJob = null
        controller?.destroy()
        controller = null
    }

    /** 强制本地开关的显隐与状态：无可用远端 / 测试模式时隐藏；当前为本地（手动/回退）时高亮。 */
    private fun updateForceLocalToggle() {
        val btn = forceLocalToggle ?: return
        val remoteAvailable = !inTestMode && remoteQueue.isNotEmpty()
        btn.visibility = if (remoteAvailable) View.VISIBLE else View.GONE
        val active = voiceMode != VoiceMode.REMOTE
        val color = if (active) resolveColor(R.color.qs_accent) else resolveColor(R.color.qs_text_secondary)
        val d = ContextCompat.getDrawable(this, R.drawable.ic_local_mode)?.mutate()
        d?.setTint(color)
        btn.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
    }

    private suspend fun makeLocalRecognizer(prefs: SharedPreferences): SpeechRecognizer {
        val dir = File(getExternalFilesDir(null) ?: filesDir, VoiceModelManager.MODEL_DIR_NAME)
        val recConfig = RecognitionConfig(
            decodingMethod = prefs.getString(QuickSendPrefs.VOICE_DECODING_METHOD, RecognitionConfig.DEFAULT_DECODING_METHOD) ?: RecognitionConfig.DEFAULT_DECODING_METHOD,
            maxActivePaths = readIntPref(prefs, QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS, RecognitionConfig.DEFAULT_MAX_ACTIVE_PATHS),
            blankPenalty = readFloatPref(prefs, QuickSendPrefs.VOICE_BLANK_PENALTY, RecognitionConfig.DEFAULT_BLANK_PENALTY),
            endpointSilence = readFloatPref(prefs, QuickSendPrefs.VOICE_ENDPOINT_SILENCE, RecognitionConfig.DEFAULT_ENDPOINT_SILENCE),
            endpointMaxUtterance = readFloatPref(prefs, QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER, RecognitionConfig.DEFAULT_ENDPOINT_MAX_UTTERANCE),
            numThreads = readIntPref(prefs, QuickSendPrefs.VOICE_NUM_THREADS, RecognitionConfig.DEFAULT_NUM_THREADS),
            provider = prefs.getString(QuickSendPrefs.VOICE_PROVIDER, RecognitionConfig.DEFAULT_PROVIDER) ?: RecognitionConfig.DEFAULT_PROVIDER
        )
        val names = SherpaModelNames()
        val rec = SherpaModelHolder.getOrLoad(dir, names, recConfig)
        return SherpaRecognizer(rec)
    }

    // 设置页识别参数经编辑框以「字符串」写入 SharedPreferences，但此处需要强类型值。
    // 直接 getInt/getFloat 在值实际为 String 时会抛 ClassCastException（远端失败回退本地时崩溃），
    // 这里按存储类型兼容读取，解析失败回退默认值。
    private fun readIntPref(prefs: SharedPreferences, key: String, default: Int): Int =
        when (val v = prefs.all[key]) {
            null -> default
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }

    private fun readFloatPref(prefs: SharedPreferences, key: String, default: Float): Float =
        when (val v = prefs.all[key]) {
            null -> default
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }

    private fun createAndStartController(
        onFinal: ((String) -> Unit)? = null,
        factory: suspend () -> SpeechRecognizer
    ) {
        val ctrl = VoiceController(
            recognizerFactory = factory,
            remote = { remoteService },
            onSessionEnd = { mainHandler.post { stopSelf() } },
            onFinalResult = onFinal
        )
        controller = ctrl
        collectJob?.cancel()
        collectJob = scope.launch {
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
                st.text = buildStatusText(getString(R.string.voice_initializing))
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            VoiceUiState.Listening -> {
                st.text = buildStatusText(getString(R.string.voice_listening))
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            is VoiceUiState.Partial -> {
                pt.text = state.text
                st.text = buildStatusText(getString(R.string.voice_listening))
                pauseBtn?.text = getString(R.string.voice_pause)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            is VoiceUiState.Paused -> {
                pt.text = state.text
                st.text = buildStatusText(getString(R.string.voice_paused))
                pauseBtn?.text = getString(R.string.voice_resume)
                backspaceBtn?.visibility = View.VISIBLE
                finishBtn?.visibility = View.VISIBLE
            }
            VoiceUiState.Finishing -> st.text = buildStatusText(getString(R.string.voice_committing))
            is VoiceUiState.Error -> {
                // 链式策略——
                //   链未耗尽：当前后端失败即试下一个（不论错误类型）；
                //   链已耗尽：鉴权/满载不静默回退本地（明确提示），用户可点「强制本地」；其它（网络不通等）自动回退本地（[NL]）；
                //   测试模式：提示失败并关闭；
                //   已在本地模式的错误直接展示文案。
                when {
                    inTestMode -> {
                        st.text = state.message
                        Toast.makeText(
                            this,
                            getString(R.string.voice_test_failed_msg, state.message),
                            Toast.LENGTH_LONG
                        ).show()
                        mainHandler.post { closeAndStop() }
                    }
                    voiceMode == VoiceMode.REMOTE && queueIndex < remoteQueue.lastIndex -> {
                        switchToNextRemote()
                        return
                    }
                    voiceMode == VoiceMode.REMOTE && state.kind == ErrorKind.RemoteAuth -> {
                        val msg = getString(R.string.voice_remote_auth_error)
                        st.text = buildStatusText(msg)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    voiceMode == VoiceMode.REMOTE && state.kind == ErrorKind.RemoteOverload -> {
                        st.text = buildStatusText(getString(R.string.voice_remote_overload_error))
                    }
                    voiceMode == VoiceMode.REMOTE -> {
                        switchToLocalMode(VoiceMode.REMOTE_FALLBACK_LOCAL, "all remotes failed (${state.kind})")
                        return
                    }
                    else -> st.text = state.message
                }
            }
            VoiceUiState.NotReady -> showPrompt(getString(R.string.voice_model_not_ready))
        }
    }

    private fun buildStatusText(text: String): CharSequence {
        // 仅给模式前缀 [L]/[N]/[NL] 上色；状态正文（"正在聆听"等）保持 TextView 默认
        // qs_text_secondary 不被染色。前缀用 qs_accent（蓝，随日夜切换、不过亮、与整体协调）；
        // 回退模式下 N 用 qs_danger（红，提示远端失败）、L 用 qs_accent。
        val accent = ContextCompat.getColor(this, R.color.qs_accent)
        val danger = ContextCompat.getColor(this, R.color.qs_danger)
        return when (voiceMode) {
            VoiceMode.LOCAL -> prefixed("[L] ", text, accent)
            VoiceMode.REMOTE -> prefixed("[N] ", text, accent)
            VoiceMode.REMOTE_FALLBACK_LOCAL -> SpannableStringBuilder().apply {
                append("[")
                append("N")
                setSpan(ForegroundColorSpan(danger), 1, 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("L")
                setSpan(ForegroundColorSpan(accent), 2, 3, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("] ")
                append(text)
            }
        }
    }

    private fun prefixed(prefix: String, text: String, color: Int): SpannableStringBuilder =
        SpannableStringBuilder().apply {
            append(prefix)
            setSpan(ForegroundColorSpan(color), 0, prefix.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(text)
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
        // 强制本地开关：仅当远端（网络）模式可用时显示，仅当前会话生效（关闭语音后随服务重置）。
        val forceToggle = TextView(this).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_local_mode, 0, 0, 0)
            contentDescription = getString(R.string.voice_force_local_desc)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            visibility = View.GONE
            setOnClickListener { onForceLocalClicked() }
        }
        forceLocalToggle = forceToggle
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(2), dp(6))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
            addView(forceToggle)
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
        buttonRow?.removeAllViews()
        buttonRow?.addView(
            makeButton(getString(R.string.voice_open_settings), secondary = false) { openSettings() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        buttonRow?.visibility = View.VISIBLE
        partialText?.text = ""
        statusText?.text = ""
    }

    /** 测试模式收到最终结果：判定是否含「测试」→ 回写 tested → 提示 → 关闭。 */
    private fun onTestFinal(text: String) {
        val pass = text.contains("测试")
        val tb = testBackend
        if (tb != null) RemoteBackendStore.setTested(this, tb.id, pass)
        val msg = if (pass) getString(R.string.voice_test_passed)
        else getString(R.string.voice_test_failed_msg, text.ifBlank { getString(R.string.voice_test_no_result) })
        VoiceLog.i(TAG, "test final: \"$text\" → pass=$pass")
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            closeAndStop()
        }
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
        forceLocalToggle = null
    }

    override fun onDestroy() {
        VoiceLog.i(TAG, "onDestroy")
        runCatching { if (registered) remoteService?.unregisterInputWindowStateListener(inputWindowListener) }
        registered = false
        collectJob?.cancel()
        collectJob = null
        controller?.destroy()
        controller = null
        removeOverlay()
        // 用户关闭语音输入：释放本会话加载的本地模型内存（含远端失败/强制本地的回退场景）。
        runCatching { SherpaModelHolder.release() }
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
        /** 单后端测试模式开关（intent extra，boolean）。 */
        const val EXTRA_TEST_MODE = "test_mode"
        /** 待测后端 JSON（intent extra，[RemoteBackend] 序列化串）。 */
        const val EXTRA_TEST_BACKEND_JSON = "test_backend_json"
        private const val CHANNEL_ID = "voice_input"
        private const val NOTIF_ID = 0x7e01
        private const val TAG = "VoiceOverlay"
    }
}
