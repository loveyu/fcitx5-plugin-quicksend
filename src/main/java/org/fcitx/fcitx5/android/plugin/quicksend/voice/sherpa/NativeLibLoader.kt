/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa

import android.content.Context
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceLog
import java.io.File

/**
 * 动态加载 Sherpa-ONNX 的 4 个原生库（不再随 APK 打包）。
 *
 * Sherpa 的 [com.k2fsa.sherpa.onnx.OnlineRecognizer] 在其静态块里调用
 * `System.loadLibrary("sherpa-onnx-jni")`，而该调用只在类加载器的 native 库目录里查找。
 * 由于 APK 不再打包 .so，必须：
 *   1. 先按依赖顺序 [LIB_LOAD_ORDER] `System.load` 各 .so（含 jni 本体），让它们进入链接器命名空间；
 *   2. 通过反射把 .so 所在目录注入 [dalvik.system.DexPathList] 的 `nativeLibraryPathElements`，
 *      使上面那个 `System.loadLibrary` 能解析到 jni 路径（随后 ART 按 path 去重，直接命中已加载实例）。
 *
 * 一个进程内 .so 只能加载一次、不可卸载；更换版本需重启 App（见 [NativeLibManager]）。
 */
object NativeLibLoader {

    private const val TAG = "NativeLib"

    /** 依赖加载顺序（DT_NEEDED）：onnxruntime ← c-api ← cxx-api ← jni。 */
    private val LIB_LOAD_ORDER = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so"
    )

    @Volatile
    private var loadedVersion: String? = null

    private val lock = Any()

    /** 是否已在本进程内加载过 .so。 */
    val isLoaded: Boolean get() = loadedVersion != null

    /** 已加载的版本标识（源 URL）；未加载返回 null。 */
    fun loadedVersion(): String? = loadedVersion

    /**
     * 加载 [libDir] 中的 4 个 .so，并记录 [version] 标识。
     *
     * - 已加载同版本：直接返回 true。
     * - 已加载其它版本：无法热替换，返回 false（调用方应提示用户重启）。
     * - 未加载：执行 System.load + 注入路径；成功返回 true，失败返回 false。
     *
     * 仅做加载（毫秒级），不做下载/解压——下载由 [NativeLibManager] 负责。
     */
    fun ensureLoaded(context: Context, libDir: File, version: String): Boolean {
        synchronized(lock) {
            val current = loadedVersion
            if (current == version) return true
            if (current != null) {
                VoiceLog.w(
                    TAG,
                    "already loaded '$current', cannot hot-swap to '$version' — restart required"
                )
                return false
            }
            return try {
                // 1) 注入目录到类加载器，使 OnlineRecognizer 静态块的 loadLibrary 能找到 jni 库
                val cl = context.classLoader ?: ClassLoader.getSystemClassLoader()
                if (!injectNativeLibDir(cl, libDir)) {
                    VoiceLog.e(TAG, "inject native lib dir failed: $libDir")
                    return false
                }
                // 2) 按依赖顺序 System.load 全部 .so（jni 依赖前三个已先行加载）
                for (name in LIB_LOAD_ORDER) {
                    val f = File(libDir, name)
                    require(f.isFile && f.length() > 0) { "missing or empty native lib: ${f.absolutePath}" }
                    VoiceLog.i(TAG, "System.load(${f.name})")
                    System.load(f.absolutePath)
                }
                loadedVersion = version
                VoiceLog.i(TAG, "native libs loaded (version=$version)")
                true
            } catch (t: Throwable) {
                VoiceLog.e(TAG, "native lib load failed: ${t.message}", t)
                false
            }
        }
    }

    /**
     * 反射把 [dir] 加入 [dalvik.system.BaseDexClassLoader] 的
     * `nativeLibraryPathElements`（NativeLibraryElement[]）。API 26+ 适用。
     */
    private fun injectNativeLibDir(loader: ClassLoader, dir: File): Boolean {
        return try {
            val baseCls = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseCls.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(loader) ?: return false
            val plc = pathList.javaClass // dalvik.system.DexPathList

            val elemsField = plc.getDeclaredField("nativeLibraryPathElements").apply { isAccessible = true }
            val old = elemsField.get(pathList) as Array<*>
            if (old.isEmpty()) return false

            // NativeLibraryElement 是 DexPathList 的嵌套静态类，类名随版本/混淆不确定；
            // 直接从现有数组的组件类型取，避免硬编码类名（旧写法 "dalvik.system.NativeLibraryElement" 会 CNFE）。
            val elemCls = old.javaClass.componentType
                ?: Class.forName("dalvik.system.DexPathList\$NativeLibraryElement")

            // 若已注入过同目录则跳过
            if (anyElementPointsTo(elemCls, old, dir)) return true

            // 构造 NativeLibraryElement(File)（目录形式）。该构造为包私有，须用
            // getDeclaredConstructor + setAccessible(true)；个别版本只有双参构造则回退。
            val newElem = runCatching {
                elemCls.getDeclaredConstructor(File::class.java).apply { isAccessible = true }.newInstance(dir)
            }.getOrElse {
                elemCls.getDeclaredConstructor(File::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(dir, null)
            }

            val merged = java.lang.reflect.Array.newInstance(elemCls, old.size + 1) as Array<Any?>
            merged[0] = newElem
            for (i in old.indices) merged[i + 1] = old[i]
            elemsField.set(pathList, merged)

            // 同步 nativeLibraryDirectories（保持一致性；字段不存在则忽略）
            runCatching {
                val dirsField = plc.getDeclaredField("nativeLibraryDirectories").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (dirsField.get(pathList) as? MutableList<File>)?.let { dirs ->
                    if (dirs.none { it.absolutePath == dir.absolutePath }) dirs.add(0, dir)
                }
            }
            VoiceLog.i(TAG, "injected native lib dir: $dir")
            true
        } catch (t: Throwable) {
            VoiceLog.e(TAG, "injectNativeLibDir failed: ${t.message}", t)
            false
        }
    }

    private fun anyElementPointsTo(elemCls: Class<*>, elements: Array<*>, dir: File): Boolean = runCatching {
        val pathField = elemCls.getDeclaredField("path").apply { isAccessible = true }
        elements.any { (pathField.get(it) as? File)?.absolutePath == dir.absolutePath }
    }.getOrDefault(false)
}
