package org.fcitx.fcitx5.android.plugin.quicksend.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendDao
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendDatabase
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

/**
 * 快捷发送数据管理器单例。
 *
 * 在 [PluginApplication.onCreate] 中调用 [init]，之后通过 [items] 观察全量列表。
 */
object QuickSendManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {

    const val MAX_ENTRIES = 500

    private var dao: QuickSendDao? = null

    private val _items = MutableStateFlow<List<QuickSendEntry>>(emptyList())
    val items: StateFlow<List<QuickSendEntry>> = _items.asStateFlow()

    fun init(context: Context) {
        if (dao != null) return
        val db = Room.databaseBuilder(
            context.applicationContext,
            QuickSendDatabase::class.java,
            QuickSendDatabase.NAME
        ).fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true).build()
        dao = db.quickSendDao()
        launch { reload() }
    }

    private suspend fun reload() {
        _items.value = dao?.allEntries() ?: emptyList()
    }

    suspend fun count(): Int = dao?.count() ?: 0

    suspend fun isFull(): Boolean = count() >= MAX_ENTRIES

    /** 新增条目。达到上限返回 false。 */
    suspend fun add(label: String, segments: List<ContentSegment>, sendMode: Int): Boolean {
        val d = dao ?: return false
        if (d.count() >= MAX_ENTRIES) return false
        val now = System.currentTimeMillis()
        d.insert(
            QuickSendEntry(
                label = label,
                segments = segments,
                sendMode = sendMode,
                createdAt = now,
                updatedAt = now
            )
        )
        reload()
        return true
    }

    /** 更新条目（保留 createdAt）。 */
    suspend fun update(
        id: Long,
        label: String,
        segments: List<ContentSegment>,
        sendMode: Int,
        useCount: Int
    ) {
        val d = dao ?: return
        val existing = d.get(id)
        d.update(
            QuickSendEntry(
                id = id,
                label = label,
                segments = segments,
                sendMode = sendMode,
                useCount = useCount,
                createdAt = existing?.createdAt ?: 0L,
                updatedAt = System.currentTimeMillis()
            )
        )
        reload()
    }

    suspend fun incrementUse(id: Long) {
        dao?.incrementUseCount(id, System.currentTimeMillis())
        reload()
    }

    suspend fun delete(id: Long) {
        val d = dao ?: return
        d.get(id)?.let { d.delete(it) }
        reload()
    }
}
