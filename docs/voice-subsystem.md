# 语音子系统

**本地流式中文识别**（Sherpa-ONNX，默认离线）+ 可选**远端多后端 ASR**（streaming-asr-server / 腾讯实时 V1 通用引擎 / V2 大模型引擎，按优先级链式尝试、全失败回退本地），浮层驱动，识别结果跨进程注入 host 输入框。

## 启动与生命周期

```
host 语音按钮
   └─▶ startForegroundService(VoiceOverlayService, action=START)
          VoiceOverlayService（前台服务，foregroundServiceType=microphone，满足 Android 14 后台录音）
            1) onCreate → startForegroundCompat（建通知渠道 + 前台通知）
            2) onStartCommand(START) → 绑定 host IQuickSendService（与 QuickSendOverlayService 同 action）
            3) showOverlay() → 弹浮层（标题/关闭/实时文本/状态/[暂停/退格/完成]按钮）
            4) evaluateAndStart():
                 缺 RECORD_AUDIO → 提示并给「去设置」按钮
                 模型未就绪        → 提示并给「去设置」按钮
                 否则              → startVoice() 建 VoiceController 并 ctrl.start()
           会话结束（onSessionEnd）→ mainHandler.post { stopSelf() }
```

- 启动由 host 触发；本服务 `exported=true` 且 `permission=${fcitxAppId}.permission.PLUGIN`（签名级），仅同签名 host 可拉起。
- `onDestroy` 顺序：`controller.destroy()` → 移除浮层 → 解绑 host → `scope.cancel()` → `stopForeground`。

## 编排：VoiceController

`VoiceController` 串起识别器与文本注入，`VoiceUiState` 流驱动浮层 UI：

| 状态 | 含义 | 文本注入 |
|------|------|----------|
| `Idle` | 空闲/会话结束 | — |
| `Initializing` | 模型首次加载或流/录音创建中 | — |
| `Listening` | 已开始录音，尚无结果 | — |
| `Partial(text)` | 流式中间结果 | `setComposingText(text)`（写组合区） |
| `Paused(text)` | 暂停录音，保留 stream、组合区文本与叠加层显示 | — |
| `Finishing` | 正在收尾 | — |
| `Error(msg)` | 出错 | — |
| `NotReady` | 模型文件未下载 | — |

- `start()`：首次调用时经 `SherpaModelHolder.getOrLoad(modelDir, names, config)` 获取共享 `OnlineRecognizer`（按 config 签名缓存，参数变更时自动释放并重载）；建 `SherpaRecognizer` 走 `Dispatchers.IO`；失败置 `Error`。若当前为 `Paused` 则直接 `resumeRecording()`（零延迟恢复）。
- `pause()`：`recognizer.pauseRecording()`，停止 AudioRecord 但保留 `OnlineStream` 不销毁；**不清空**组合区文本，状态变为 `Paused`。
- `finish()`：触发 `recognizer.stop()`（flush 最终结果），会话结束。
- `close()`：`recognizer.cancel()`（丢弃）并 `setComposingText("")` 清空组合区，会话结束。
- `backspace()`：**流式阶段**递增 `partialBackspaceOffset`，从 `rawPartialText` 末尾逐字截断，重发 `setComposingText(adjusted)` 同步更新叠加层与输入框组合区。**提交后**受 `committedVoiceCharCount` 追踪，发送 `KEYCODE_DEL`（每次删一个语音提交的字符）。
- `destroy()`：`collectJob.cancel()` + `recognizer.releaseNow()`（同步强制释放 stream/AudioRecord，不释放共享模型）+ `scope.cancel()`。
- **Final 路径**：`RecognitionEvent.Final` → 先经 `refiner.refine`（默认 `NoOpRefiner` 原样返回）→ `commitText(refined, -1)` → 成功后 `committedVoiceCharCount += refined.length`。
- 退格仅删除语音提交的字符，避免误删用户手动输入的内容。
- 所有 IPC 调用都 `withContext(Dispatchers.IO)`：host 端可能把 `setComposingText` 派发到 IMS 主线程而阻塞。

## 识别器：SherpaRecognizer（核心，最容易踩坑）

- **模型不内嵌**：构造函数接受 `OnlineRecognizer`（由 `SherpaModelHolder` 提供），不再自行加载模型文件。
- **录音**：16kHz 单声道 PCM16，每 100ms（`sampleRate/10` ≈ 1600 采样）读一帧；转 float（÷32768）喂 `OnlineStream.acceptWaveform`；`isReady` 则 `decode`；结果变化才发 `Partial`。
- **配置**：由 `SherpaModelHolder` 构建 `OnlineRecognizerConfig` + `OnlineTransducerModelConfig`（encoder/decoder/joiner + tokens），`numThreads=2`，`enableEndpoint=true`（端点/VAD 检测）。
- **暂停/恢复**：`pauseRecording()` 置 `@Volatile paused=true` → 录音循环中停止 AudioRecord 并 sleep(100)；`resumeRecording()` 置 `paused=false` → 重启 AudioRecord 继续同一 `OnlineStream`。**不销毁任何原生对象**。
- **收尾**：`stop` 置 `commitFinal=true` → 循环退出后 `inputFinished()` + 尽力 `decode` → 取 `getResult` 发 `Final`；`cancel` 置 `commitFinal=false` → 直接清理不产结果。
- `releaseNow()` 仅释放 stream + AudioRecord，**不释放共享的 `OnlineRecognizer`**（由 `SherpaModelHolder` 管理）。

### ⚠️ native 线程铁律（防 use-after-free）

**所有 stream/record 原生对象只在唯一的 `nativeThread`（`Thread("sherpa-native")`）上创建、使用、释放。** `start`/`stop`/`cancel`/`releaseNow` 仅翻转 `@Volatile` 标志（`running` / `commitFinal` / `paused`）并 `join` 该线程，**绝不跨线程直接接触原生对象**。

- `runNativeSession(ready)`：在 nativeThread 内建 stream + AudioRecord → 录音循环（含 pause 逻辑）→ 按 `commitFinal` 收尾 → `finally { cleanup() }`（仅在此线程释放 stream/record）。
- `awaitNativeThread()` / `releaseNow()`：`join(2_000)` 等 nativeThread 退出（~100ms 内）；超时仅 warning 不强杀。
- 历史教训：曾因在识别循环仍在 `acceptWaveform/decode` 时释放原生句柄，导致 `acceptWaveform` 处 native SIGSEGV（见 commit `e35f5a0`）。改 native 后若再动这块，务必保持「单线程接触原生对象」不变式。
- 初始化用 `CompletableDeferred<Boolean>`：stream/AudioRecord 建好才 `complete(true)`；`start` 在 `ready.await()` 处等，失败抛 `IllegalStateException`（`VoiceController` 捕获置 `Error`）。

## 模型持有者：SherpaModelHolder（进程级单例）

模型文件加载为 `OnlineRecognizer` 只需一次；`RecognitionConfig` 变更时自动重载：

- `getOrLoad(modelDir, names, config)`：`@Volatile` 快速路径（检查 modelDir + config 签名） → 变更则 `synchronized` 双重检查 + 释放旧模型 + `withContext(Dispatchers.IO)` 重新加载。
- `config.toSignature()` 作为缓存键，包含 7 个参数：`decodingMethod`、`maxActivePaths`、`blankPenalty`、`endpointSilence`、`endpointMaxUtterance`、`numThreads`、`provider`。
- 加载代码 `SherpaModelFiles.resolve()` + `OnlineRecognizerConfig`（含 `EndpointConfig`/`EndpointRule`）+ `new OnlineRecognizer(config)`。
- `release()`：同步释放，仅应在插件进程销毁时调用。
- 共享模型后，`SherpaRecognizer` 的 `cleanup()` / `releaseNow()` 均不触碰此实例。

## 模型管理：VoiceModelManager

- **目录**：应用专用外部目录 `sherpa/zh-large-2025/`（`getExternalFilesDir(null)` 退化到 `filesDir`）。
- **就绪判定**：`SherpaModelFiles.resolve(dir, names)` 能解析出 encoder/decoder/joiner/tokens 四文件即就绪。
- **下载**：`download(context, baseUrl, names, proxy)`
  - URL 以 `.tar.bz2` 结尾 → `downloadAndExtractTarBz2`：下载单文件归档，进度 0-100%（含"解压中…"阶段），用 commons-compress 解 bzip2 + tar，跳过顶层目录提取 4 个模型文件。
  - 否则 → `downloadIndividualFiles`：旧版逐文件下载（兼容 HuggingFace base URL），文件名带 `· n/m` 进度标签。
  - 状态经 `DownloadState` 流（`Idle/Downloading(percent, label)/Ready/Failed`）。
- **默认源**：GitHub Releases `k2-fsa/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2`；设置页可改 URL（切镜像/旧模型）。
- `cancel()` 取消下载并回 `Idle`；`delete()` 删模型文件（含 `.part`）；`refresh()` 在设置页 `onResume` 刷新就绪态。
- 文件名（encoder/decoder/joiner/tokens）默认 zh Large int8 2025-06，可在设置页改 —— `QuickSendPrefs` 的 `VOICE_NAME_*` 键。

## 动态加载 native 库：NativeLibManager + NativeLibLoader

APK 不再打包 Sherpa 的 4 个 `.so`（`libonnxruntime/libsherpa-onnx-{c-api,cxx-api,jni}.so`）。用户启用本地识别时按需下载、`System.load` 动态加载。

- **`NativeLibManager`**（下载/版本，镜像 `VoiceModelManager`）：
  - 目录 `<filesDir>/native_libs/sherpa/<deviceAbi>/`；`deviceAbi` 取 `Build.SUPPORTED_ABIS` 中首个已发布项（`arm64-v8a/armeabi-v7a/x86_64`），都不命中回退 `arm64-v8a`。
  - **默认地址构建期生成**：`BuildConfig.NATIVE_LIB_DEFAULT_URL`（`build.gradle.kts` 用当前 tag + `sherpaAarVersion` 拼出 GitHub Release 下 `sherpa-onnx-<ver>-{ABI}.zip`），`{ABI}` 运行时替换。设置页可改（键 `VOICE_NATIVE_LIB_URL`）。
  - **与模型下载共用代理**：`download(ctx, url, ProxyConfig.fromUri(voice_proxy_uri))`，走 `VoiceHttp.client(proxy)`。下 zip → 解压 4 个 `.so`（临时文件 + 原子 rename）→ 写 `.source_url` 版本标记。
  - 状态流复用 `DownloadState`；`isReady/downloadedVersion/needsRestart`；`loadIfReady(ctx): LoadResult`（Loaded/NotDownloaded/NeedsRestart/Failed）。
- **`NativeLibLoader.ensureLoaded(ctx, libDir, version)`**（加载，仅做毫秒级加载不做下载）：
  1. 反射把 `libDir` 注入 `DexPathList.nativeLibraryPathElements`（构造 `NativeLibraryElement(File)` 前置）。
  2. 按依赖序 `System.load`：`libonnxruntime → libsherpa-onnx-c-api → libsherpa-onnx-cxx-api → libsherpa-onnx-jni`。
  3. 记 `loadedVersion`。同版本重复调用幂等；已加载其它版本返回 false（`NeedsRestart`）。
- **为何要反射注入路径**：`OnlineRecognizer`（AAR 内、不可改）静态块 `System.loadLibrary("sherpa-onnx-jni")` 只在类加载器 native 目录查找；APK 不打包则 `findLibrary` 返空 → `UnsatisfiedLinkError`。先把 4 个 .so `System.load` 进命名空间，再把目录注入 `nativeLibraryPathElements`，`loadLibrary` 即解析到我们的 jni 路径、随 path 去重命中已加载实例。
- **反射坑**：`NativeLibraryElement` 是 `DexPathList` 的**嵌套**类、且 `(File)` 构造**包私有**——须 `getDeclaredConstructor`+`setAccessible(true)`，并用数组组件类型取类（勿硬编码 `dalvik.system.NativeLibraryElement`，会 CNFE）。
- **加载时机**：`VoiceOverlayService.makeLocalRecognizer` 先 `loadIfReady(this)`；非 Loaded 抛异常（`voice_native_not_ready` / `voice_native_needs_restart` / 失败），由切换/启动流程捕获提示。
- **重启策略**：`.so` 一个进程只能加载一次、不可卸载；用户下载新版后 `needsRestart=true`，需重启 App 才能用新 .so（设置页文案提示）。
- **AVD 实测要点**：模拟器 `10.0.2.2` 在本机若被透明代理拦截可改用 `adb reverse tcp:8899 tcp:8899` + `http://127.0.0.1:8899/...`；debug 构建有直达语音设置页的桌面图标（`src/debug/AndroidManifest.xml`）与「测试加载（debug）」按钮，可无需 host 主程序验证下载+System.load+`OnlineRecognizer` 类初始化。


## 网络/代理

- `ProxyConfig.fromUri(uri)`：单 URI 字符串解析为代理结构。`http(s)://` → HTTP 代理；`socks/socks4/socks5://` → SOCKS 代理；支持 `user:pass@host:port`；空串/非法 → `NONE`。默认端口 HTTP=8080、SOCKS=1080。
- `VoiceHttp.client(proxy)`：按 `ProxyConfig` 构 OkHttp（仅用于模型下载；识别是本地、不走网络）。
- 旧版多字段代理键（`QuickSendPrefs.VOICE_PROXY_*_LEGACY`）仅用于迁移到 `VOICE_PROXY_URI`，新代码不再写入。

## 日志

`VoiceLog`（`VoiceLog.init` 在 `PluginApplication.onCreate` 调用）统一打点到应用专用外部目录文件，设置页可经 `FileProvider`（`${applicationId}.fileprovider`，路径见 `res/xml/file_paths.xml`）分享调试日志。Tag 例：`VoiceCtrl` / `SherpaRec` / `VoiceOverlay` / `VoiceModel`。

## 识别参数：RecognitionConfig

设置页"识别参数"区域提供 7 个可调参数，储存在 `QuickSendPrefs` 的 `VOICE_*` 键中，由 `VoiceOverlayService.startVoice()` 读取构造 `RecognitionConfig` 传给 `SherpaModelHolder`：

| 参数 | prefs key | 默认值 | 说明 |
|------|-----------|--------|------|
| `decodingMethod` | `voice_decoding_method` | `greedy_search` | 解码方式：`greedy_search` 最快；`modified_beam_search` 多路径搜索更准且支持热词 |
| `maxActivePaths` | `voice_max_active_paths` | `4` | 仅 beam search 生效，越大越准越慢 |
| `blankPenalty` | `voice_blank_penalty` | `0.0` | blank 惩罚系数：>0 降低漏字，<0 减少多字 |
| `endpointSilence` | `voice_endpoint_silence` | `1.2` | 端点静音阈值（秒），检测到语音后连续静音超此值自动结束 |
| `endpointMaxUtterance` | `voice_endpoint_max_utter` | `20.0` | 单句最长时长（秒），超时强制结束 |
| `numThreads` | `voice_num_threads` | `2` | ONNX 推理线程数，建议不超过大核数 |
| `provider` | `voice_provider` | `cpu` | 推理后端：`cpu`（稳定）或 `xnnpack`（部分设备更快） |

- 每个参数旁有 **?** 图标，点击弹出说明对话框（参数描述 + 默认值 + 推荐值）。
- "重置默认"按钮同步清除识别参数 prefs。
- 参数变更后需要重新触发模型加载（`SherpaModelHolder` 按 `config.toSignature()` 检测差异并自动重载）。

## ⚠️ 网络 IO 线程纪律

**远端 ASR 识别器内的所有 HTTP 调用（OkHttp `execute()` 等同步阻塞操作）必须在 `Dispatchers.IO` 上执行，绝不能压在主线程。**

- HTTP REST 型识别器（如 `GlmAsrRecognizer`）：`stop()` 内上传音频并解析 SSE 响应的环节是同步阻塞的，必须用 `withContext(Dispatchers.IO) { ... }` 包裹。
- WebSocket 型识别器（`BaseWsStreamingRecognizer` 子类）：OkHttp WebSocket 自身在后台线程收发，但若未来新增同步 HTTP 调用（如 pre-signed URL 获取、鉴权令牌刷新等），同样必须切至 IO 线程。
- 违反 → `NetworkOnMainThreadException`（Android StrictMode 强制拒绝主线程网络）。

## 远端 ASR（多后端，链式回退）

远端不再是单后端 + 三键 prefs，而是**可插拔多后端**体系：

- **配置模型** `RemoteBackend`（sealed，`voice/remote/RemoteBackend.kt`）：`StreamingAsrServerBackend`（自建 streaming-asr-server）/ `TencentAsrV1Backend`（腾讯实时语音识别 V1，**通用引擎**，默认 `16k_zh`）/ `TencentAsrV2Backend`（腾讯实时语音识别 V2，**仅大模型引擎**，默认 `16k_zh_en_2.0`）。V1/V2 字段同构，公共部分抽到 `TencentAsrBackend` 接口供 UI 共享表单。公共字段 `id/name/enable/tested`；新增类型再加一个 data class + 识别器即可（kotlinx-serialization 自动带 `type` 判别）。
- **持久化** `RemoteBackendStore`（`voice/remote/RemoteBackendStore.kt`）：JSON 数组存 `QuickSendPrefs.VOICE_REMOTE_BACKENDS`。`activeBackends()` = `enable && tested`，按存储顺序（= 优先级）。
- **识别器**：`BaseWsStreamingRecognizer`（`voice/remote/`）抽公共骨架（16k PCM 直采 + 单 nativeThread + 收尾/软结束/错误分类模板方法 + `stableText`/`appendStable`/`setPartial` 多句累积助手）；`StreamingAsrServerRecognizer` / `TencentAsrV1Recognizer` / `TencentAsrV2Recognizer`（`voice/remote/{streaming,tencent}/`）实现协议差异。`RemoteBackend.recognizer()` 工厂映射配置→识别器。
- **链式回退**（在 `VoiceOverlayService`）：会话开始取 `activeBackends()` 为优先级链；当前后端失败且链未耗尽 → 试下一个；链耗尽后 `ErrorKind.Generic` → 回退本地（`[NL]`），`RemoteAuth/RemoteOverload` → 仅提示不静默回退（与原单后端语义一致）。无后端 → 直接本地。
- **腾讯客户端直连（V1/V2 共用签名）**：签名（HMAC-SHA1+Base64）在客户端算，拼进 `wss://asr.cloud.tencent.com/asr/v2/<appid>?<字典序参数>&signature=<urlencode>`。V1 与 V2 **同址、同签名算法**，逻辑抽到纯 JVM 的 `TencentV2Signing`（自带 base64，便于单测，避开 minSdk 24 与 java.util.Base64 需 API 26 的冲突）。差异只在响应：V1 按 `result.slice_type`（2=稳态）/`voice_text_str`，V2 按 `sentences.sentence_type`（1=稳态）。code 4002/4003/4004/4005→鉴权、4006→满载、4008→软结束。
- **⚠️ Final = 会话结束（多句累积铁律）**：`VoiceController.handle(Final)` 提交后**必定** `endSession()`（→ `stopSelf`），且 `BaseWsStreamingRecognizer.stop()` 只在 `finalResult` 完成后下发**一次** Final。故按句返回的后端（腾讯 V1/V2）**绝不能逐句发 Final**——否则首句就终止会话（后续句丢失）并重复提交。正确做法：稳态句 `appendStable` 只更新展示（Partial），累积到 `stableText`；会话结束（`final==1` 或 stop 超时软结束）才 `markFinal(Final(全量稳态))` 一次性提交，与 Sherpa/streaming-asr-server「单 Final」语义一致。
- **设置页**：`RemoteAsrSettingsActivity`（Compose + Material3，列表「启用在前 + 长按拖拽排序」+ 底部抽屉编辑按类型填参 + 单后端测试）。入口在主菜单「远端语音识别」（不再埋在本地语音输入页）。
- **单后端测试**：设置页点「测试」→ 先 upsert（稳定 id）→ 序列化后端 → `startForegroundService(VoiceOverlayService, ACTION_START, EXTRA_TEST_MODE=true, EXTRA_TEST_BACKEND_JSON)`。测试模式跳过模型就绪/启用判断、不响应输入窗隐藏、`VoiceController.onFinalResult` 旁路 host 注入；final 含「测试」→ `RemoteBackendStore.setTested(id, true)` + Toast。

## 扩展点（占位，待实现）

- `RecognizerProvider`：仍为占位接口（远端已由 `RemoteBackend` + 工厂实现，未走此接口）；后续若有更复杂的在线 Provider 抽象再启用。
- `TextRefiner`：Phase 1 仅 `NoOpRefiner`；大模型润色（自动标点 / 去口头禅 / 数字转换 / 中英空格等）留空。
- `SpeechRecognizer`：识别器抽象接口（`events: Flow<RecognitionEvent>` + `start/stop/cancel`），`SherpaRecognizer` 与 `BaseWsStreamingRecognizer` 子类是其实现。
