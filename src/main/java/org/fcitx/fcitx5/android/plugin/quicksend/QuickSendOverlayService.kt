package org.fcitx.fcitx5.android.plugin.quicksend

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.ui.SegmentFormatter

/**
 * 悬浮窗触发：屏幕边缘常驻小按钮，点击弹出快捷发送列表。
 *
 * 数据来自本进程 [QuickSendManager]；选中条目后通过 [QuickSendExecutor]
 * 跨进程调用主项目的 [IQuickSendService] 完成实际发送。
 * 需 SYSTEM_ALERT_WINDOW 权限（[Settings.canDrawOverlays]）。
 */
class QuickSendOverlayService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var listPopup: View? = null
    private var adapter: ArrayAdapter<QuickSendEntry>? = null
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            removeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        showButton()
        return START_STICKY
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

    private fun showList() {
        if (listPopup != null) return
        val wm = windowManager ?: return
        val items = QuickSendManager.items.value
        val lv = ListView(this).apply {
            divider = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(0xFFFFFFFF.toInt())
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
        runCatching { wm.addView(lv, lp) }
        listPopup = lv
    }

    private fun hideList() {
        listPopup?.let { runCatching { windowManager?.removeView(it) } }
        listPopup = null
    }

    private fun sendEntry(entry: QuickSendEntry) {
        scope.launch { withContext(Dispatchers.IO) { QuickSendExecutor.execute(entry) } }
        hideList()
    }

    private fun removeAll() {
        hideList()
        buttonView?.let { runCatching { windowManager?.removeView(it) } }
        buttonView = null
        collectJob?.cancel()
        collectJob = null
    }

    override fun onDestroy() {
        removeAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_HIDE = "org.fcitx.fcitx5.android.plugin.quicksend.HIDE"
    }
}
