package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.common.ipc.IInputWindowStateListener
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.ui.SegmentFormatter

/**
 * 悬浮窗触发：仅在输入法软键盘弹出时显示一个边缘小按钮，点击展开快捷发送列表；
 * 输入法收起时自动隐藏按钮与列表。
 *
 * 可见性来源：本服务自行绑定主项目 [IQuickSendService] 并注册
 * [IInputWindowStateListener]，由主项目在 `onWindowShown/onWindowHidden` 时回调，
 * 因此不依赖轮询、也无需常驻按钮。
 *
 * 列表数据来自本进程 [QuickSendManager]；选中条目后通过 [QuickSendExecutor]
 * 跨进程调用主项目完成实际发送。需 SYSTEM_ALERT_WINDOW 权限
 * （[android.provider.Settings.canDrawOverlays]）。
 */
class QuickSendOverlayService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fcitxAppId get() = BuildConfig.FCITX_APP_ID

    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var listPopup: View? = null
    private var adapter: ArrayAdapter<QuickSendEntry>? = null
    private var collectJob: Job? = null

    private var remoteService: IQuickSendService? = null
    private var registered = false

    /** 由主项目回调（binder 线程），转发到主线程操作窗口视图。 */
    private val listener = object : IInputWindowStateListener.Stub() {
        override fun onInputWindowShown() {
            mainHandler.post { showButton() }
        }

        override fun onInputWindowHidden() {
            mainHandler.post {
                hideList()
                hideButton()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val s = IQuickSendService.Stub.asInterface(service)
            remoteService = s
            runCatching {
                s.registerInputWindowStateListener(listener)
                registered = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            registered = false
            remoteService = null
            mainHandler.post {
                hideList()
                hideButton()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        // 订阅输入法可见性：收到 onWindowShown 才显示按钮。
        runCatching {
            bindService(
                Intent("$fcitxAppId.quicksend.IPC").setPackage(fcitxAppId),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 保持 Service 常驻以维持输入法状态监听；按钮本身由 listener 回调按需显隐。
        return START_STICKY
    }

    override fun onDestroy() {
        removeAll()
        runCatching { if (registered) remoteService?.unregisterInputWindowStateListener(listener) }
        registered = false
        runCatching { if (remoteService != null) unbindService(connection) }
        remoteService = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showButton() {
        if (buttonView != null) return
        val wm = windowManager ?: return
        val btn = TextView(this).apply {
            text = "发"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.overlay_button_bg)
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(6)
        }
        btn.setOnClickListener {
            if (listPopup != null) hideList() else showList()
        }
        runCatching { wm.addView(btn, lp) }
        buttonView = btn

        collectJob = scope.launch {
            QuickSendManager.items.collect { list ->
                adapter?.apply { clear(); addAll(list); notifyDataSetChanged() }
            }
        }
    }

    private fun hideButton() {
        buttonView?.let { runCatching { windowManager?.removeView(it) } }
        buttonView = null
        collectJob?.cancel()
        collectJob = null
    }

    private fun showList() {
        if (listPopup != null) return
        val wm = windowManager ?: return
        val items = QuickSendManager.items.value
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.empty_list, Toast.LENGTH_SHORT).show()
            return
        }
        val lv = ListView(this).apply {
            divider = null
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        val ad = object : ArrayAdapter<QuickSendEntry>(
            this@QuickSendOverlayService,
            android.R.layout.simple_list_item_1,
            items.toMutableList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                getItem(position)?.let { tv.text = SegmentFormatter.displayLabel(it) }
                tv.setPadding(dp(12), dp(10), dp(12), dp(10))
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                return tv
            }
        }
        adapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, position, _ ->
            ad.getItem(position)?.let { sendEntry(it) }
        }

        val title = TextView(this).apply {
            text = getString(R.string.overlay_list_title)
            setTextColor(Color.parseColor("#212121"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#616161"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(6), dp(10), dp(6))
            contentDescription = getString(R.string.overlay_close)
            setOnClickListener { hideList() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(2), dp(6))
            addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            )
            addView(closeBtn)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(1, Color.parseColor("#E0E0E0"))
            }
            elevation = dp(6).toFloat()
            addView(header)
            addView(
                View(this@QuickSendOverlayService).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
            addView(
                lv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            )
        }

        val lp = WindowManager.LayoutParams(
            dp(280),
            dp(420),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(6)
        }
        runCatching { wm.addView(container, lp) }
        listPopup = container
    }

    private fun hideList() {
        listPopup?.let { runCatching { windowManager?.removeView(it) } }
        listPopup = null
        adapter = null
    }

    private fun sendEntry(entry: QuickSendEntry) {
        scope.launch { withContext(Dispatchers.IO) { QuickSendExecutor.execute(entry) } }
        hideList()
    }

    private fun removeAll() {
        hideList()
        hideButton()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_HIDE = "org.fcitx.fcitx5.android.plugin.quicksend.HIDE"
    }
}
