package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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

    private var buttonGravity = Gravity.END or Gravity.BOTTOM
    private var buttonX = -1
    private var buttonY = -1
    private var positionLoaded = false

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLayoutX = 0
    private var dragStartLayoutY = 0
    private var isDragMode = false

    private val dragEnterRunnable = Runnable {
        val btn = buttonView ?: return@Runnable
        isDragMode = true
        val location = IntArray(2)
        btn.getLocationOnScreen(location)
        val lp = btn.layoutParams as WindowManager.LayoutParams
        lp.gravity = Gravity.START or Gravity.TOP
        lp.x = location[0]
        lp.y = location[1]
        windowManager?.updateViewLayout(btn, lp)
        dragStartLayoutX = location[0]
        dragStartLayoutY = location[1]
        applyDragBorder(btn as TextView)
    }

    private val dragExitRunnable = Runnable {
        isDragMode = false
        (buttonView as? TextView)?.let { removeDragBorder(it) }
    }

    private var remoteService: IQuickSendService? = null
    private var registered = false

    private val prefs by lazy { getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE) }

    private val colorKeys = setOf(
        QuickSendPrefs.OVERLAY_BG_COLOR,
        QuickSendPrefs.OVERLAY_TEXT_COLOR,
        QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT,
        QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT
    )

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in colorKeys) {
            buttonView?.let { btn ->
                mainHandler.post {
                    applyButtonColors(btn as TextView)
                    if (isDragMode) applyDragBorder(btn as TextView)
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun resolveButtonColors(): Pair<Int, Int> {
        val isNight = isNightMode()
        val bgKey = if (isNight) QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT else QuickSendPrefs.OVERLAY_BG_COLOR
        val textKey = if (isNight) QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT else QuickSendPrefs.OVERLAY_TEXT_COLOR
        val bg = prefs.getInt(bgKey, QuickSendPrefs.DEFAULT_BG_COLOR)
        val text = prefs.getInt(textKey, QuickSendPrefs.DEFAULT_TEXT_COLOR)
        return bg to text
    }

    private fun applyButtonColors(btn: TextView) {
        val (bg, text) = resolveButtonColors()
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            val size = dp(48)
            setSize(size, size)
        }
        btn.setTextColor(text)
    }

    private fun applyDragBorder(btn: TextView) {
        val (bg, _) = resolveButtonColors()
        val borderColor = if (isNightMode()) {
            Color.argb(255, 255, 80, 80)
        } else {
            Color.RED
        }
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            val size = dp(48)
            setSize(size, size)
            setStroke(dp(3), borderColor)
        }
    }

    private fun removeDragBorder(btn: TextView) {
        applyButtonColors(btn)
    }

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
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
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
        mainHandler.removeCallbacks(dragEnterRunnable)
        mainHandler.removeCallbacks(dragExitRunnable)
        removeAll()
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener) }
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
        if (!positionLoaded) loadPosition()

        val btn = TextView(this).apply {
            text = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        val (bgColor, textColor) = resolveButtonColors()
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setSize(dp(48), dp(48))
        }
        btn.setTextColor(textColor)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = buttonGravity
            x = buttonX
            y = buttonY
        }
        btn.setOnTouchListener { view, event -> onButtonTouch(view, event) }
        runCatching { wm.addView(btn, lp) }
        buttonView = btn

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            btn.post { adjustForKeyboard(btn) }
        }

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

    private fun loadPosition() {
        positionLoaded = true
        val prefs = getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE)
        buttonGravity = prefs.getInt(QuickSendPrefs.OVERLAY_GRAVITY, Gravity.END or Gravity.BOTTOM)
        buttonX = prefs.getInt(QuickSendPrefs.OVERLAY_X, dp(6))
        buttonY = prefs.getInt(QuickSendPrefs.OVERLAY_Y, dp(40))
    }

    private fun savePosition() {
        getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE).edit()
            .putInt(QuickSendPrefs.OVERLAY_GRAVITY, buttonGravity)
            .putInt(QuickSendPrefs.OVERLAY_X, buttonX)
            .putInt(QuickSendPrefs.OVERLAY_Y, buttonY)
            .apply()
    }

    private fun onButtonTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                mainHandler.removeCallbacks(dragExitRunnable)
                mainHandler.postDelayed(dragEnterRunnable, 2000L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragMode) {
                    val dx = Math.abs((event.rawX - dragStartRawX).toInt())
                    val dy = Math.abs((event.rawY - dragStartRawY).toInt())
                    if (dx > dp(4) || dy > dp(4)) {
                        mainHandler.removeCallbacks(dragEnterRunnable)
                    }
                    return true
                }
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                val lp = view.layoutParams as WindowManager.LayoutParams
                lp.x = dragStartLayoutX + dx
                lp.y = dragStartLayoutY + dy
                windowManager?.updateViewLayout(view, lp)
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(dragEnterRunnable)
                if (isDragMode) {
                    finalizePosition(view)
                    mainHandler.postDelayed(dragExitRunnable, 1000L)
                } else {
                    if (listPopup != null) hideList() else showList()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(dragEnterRunnable)
                if (isDragMode) {
                    finalizePosition(view)
                    mainHandler.postDelayed(dragExitRunnable, 1000L)
                }
                return true
            }
        }
        return false
    }

    private fun finalizePosition(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return

        val centerX = location[0] + w / 2
        val centerY = location[1] + h / 2

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val keyboardH = getKeyboardHeight(view)
        val usableH = screenH - keyboardH

        val gravX = if (centerX < screenW / 2) Gravity.START else Gravity.END
        val gravY = if (centerY < usableH / 4) Gravity.TOP else Gravity.BOTTOM

        buttonX = if (gravX == Gravity.START) location[0] else screenW - (location[0] + w)
        buttonY = if (gravY == Gravity.TOP) location[1] else usableH - (location[1] + h)
        buttonGravity = gravX or gravY

        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.gravity = buttonGravity
        lp.x = buttonX
        lp.y = buttonY
        windowManager?.updateViewLayout(view, lp)

        savePosition()
    }

    private fun getKeyboardHeight(view: View): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun adjustForKeyboard(view: View) {
        val keyboardH = getKeyboardHeight(view)
        if (keyboardH <= 0 || (buttonGravity and Gravity.BOTTOM) == 0) return
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.y = buttonY + keyboardH
        windowManager?.updateViewLayout(view, lp)
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
            divider = ColorDrawable(resolveColor(R.color.qs_overlay_divider))
            dividerHeight = dp(1)
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
                tv.setTextColor(resolveColor(R.color.qs_text_primary))
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
            setTextColor(resolveColor(R.color.qs_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(resolveColor(R.color.qs_overlay_close))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(6), dp(10), dp(6))
            contentDescription = getString(R.string.overlay_close)
            setOnClickListener { hideList() }
        }
        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = getString(R.string.overlay_open_settings)
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(this@QuickSendOverlayService, PluginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                hideList()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(2), dp(6))
            addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            )
            addView(settingsBtn)
            addView(closeBtn)
        }

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
                View(this@QuickSendOverlayService).apply { setBackgroundColor(resolveColor(R.color.qs_overlay_divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
            addView(
                lv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            )
        }

        val btnLoc = IntArray(2)
        buttonView?.getLocationOnScreen(btnLoc)
        val btnW = buttonView?.width?.takeIf { it > 0 } ?: dp(76)
        val btnH = buttonView?.height?.takeIf { it > 0 } ?: dp(76)
        val btnCenterX = btnLoc[0] + btnW / 2
        val btnCenterY = btnLoc[1] + btnH / 2

        val d = resources.displayMetrics
        val screenW = d.widthPixels
        val screenH = d.heightPixels

        val margin = dp(6)
        val popupW = dp(280)
        val popupH = dp(420)
        val isLeftSide = btnCenterX < screenW / 2

        val px: Int
        val popupGravX: Int
        if (isLeftSide) {
            popupGravX = Gravity.START
            px = btnLoc[0] + btnW + margin
        } else {
            popupGravX = Gravity.END
            px = screenW - btnLoc[0] + margin
        }
        val py = (btnCenterY - popupH / 2).coerceIn(0, screenH - popupH)

        val popupLp = WindowManager.LayoutParams(
            popupW,
            popupH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = popupGravX or Gravity.TOP
            x = px
            y = py
        }
        runCatching { wm.addView(container, popupLp) }
        listPopup = container
    }

    private fun hideList() {
        listPopup?.let { runCatching { windowManager?.removeView(it) } }
        listPopup = null
        adapter = null
    }

    private fun sendEntry(entry: QuickSendEntry) {
        // 用悬浮窗自身已建立的连接发送，不依赖输入法主动绑定插件 MainService
        // （更新插件后输入法未重连时，RemoteServiceHolder 可能为空，但本连接仍可用）。
        val remote = remoteService
        scope.launch { withContext(Dispatchers.IO) { QuickSendExecutor.execute(entry, remote) } }
        hideList()
    }

    private fun removeAll() {
        hideList()
        hideButton()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 解析语义颜色：随系统深色模式取 values/values-night 对应值。悬浮窗虽是 Service
     *  上下文，但其资源配置同样反映当前 uiMode，故可正确切换日夜。 */
    private fun resolveColor(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        const val ACTION_HIDE = "org.fcitx.fcitx5.android.plugin.quicksend.HIDE"
    }
}
