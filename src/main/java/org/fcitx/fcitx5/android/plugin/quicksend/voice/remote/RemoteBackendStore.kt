/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import java.util.UUID

/**
 * 远端 ASR 后端列表的持久化（SharedPreferences 单键存 JSON 数组，复用 kotlinx-serialization）。
 * 替代旧的三个零散键 `voice_remote_enabled/url/token`（旧配置不保留、不迁移）。
 *
 * 列表顺序即优先级；[activeBackends] 给运行时链：仅 `enable && tested`，保持存储顺序。
 */
object RemoteBackendStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(QuickSendPrefs.FILE, Context.MODE_PRIVATE)

    /** 全部后端（按存储顺序 = 优先级）。 */
    fun list(context: Context): List<RemoteBackend> {
        val raw = prefs(context).getString(QuickSendPrefs.VOICE_REMOTE_BACKENDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<RemoteBackend>>(raw) }.getOrDefault(emptyList())
    }

    /** 整表写入（拖拽改顺序后调用）。 */
    fun save(context: Context, backends: List<RemoteBackend>) {
        prefs(context).edit().putString(QuickSendPrefs.VOICE_REMOTE_BACKENDS, json.encodeToString(backends)).apply()
    }

    /** 新增或按 [RemoteBackend.id] 替换；新增项追加到末尾（最低优先级）。 */
    fun upsert(context: Context, backend: RemoteBackend) {
        val current = list(context)
        val idx = current.indexOfFirst { it.id == backend.id }
        val updated = if (idx >= 0) current.toMutableList().apply { set(idx, backend) }
        else current + backend
        save(context, updated)
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    /** 回写单个后端的 tested 标记（语音自测结束时调用）。 */
    fun setTested(context: Context, id: String, tested: Boolean) {
        val updated = list(context).map { if (it.id == id) it.withTested(tested) else it }
        save(context, updated)
    }

    /** 运行时识别链：仅启用且通过测试的后端，保持存储顺序（优先级）。 */
    fun activeBackends(context: Context): List<RemoteBackend> =
        list(context).filter { it.enable && it.tested }

    /** 生成新后端 id。 */
    fun newId(): String = UUID.randomUUID().toString()

    /** 序列化单个后端（测试模式经 intent 传递）。 */
    fun encode(backend: RemoteBackend): String = json.encodeToString(backend)

    /** 反序列化单个后端。 */
    fun decode(json: String): RemoteBackend? =
        runCatching { this.json.decodeFromString<RemoteBackend>(json) }.getOrNull()
}
