# AI 协作开发操作手册（ai-dev-playbook）

> 给 AI（Claude Code）与新人**协作开发 fcitx5-plugin-quicksend** 的操作速查：高频操作怎么做、踩坑在哪、规范是什么。
> **不重复**专题文档已详述的内容——本文只给「最常用命令 + 独家踩坑 + 入口索引」，详解一律交叉引用。
> 权威划分：组件与 IPC → [architecture.md](architecture.md)；语音管线 → [voice-subsystem.md](voice-subsystem.md)；构建/签名/CI → [build-and-release.md](build-and-release.md)；历史坑与设计权衡 → [tech-debt.md](tech-debt.md)。

## 0. 这是什么

fcitx5-android 的**独立插件 APK**（不是主项目模块），通过 AIDL IPC 与 fcitx5-android **主程序（host）**通信。两个功能：

- **QuickSend**：发送预设按键组合 / 文本（如 `Ctrl+Shift+Del`），条目存 Room。
- **语音输入**：Sherpa-ONNX 本地流式中文识别 + 可选远端多后端 ASR（streaming-asr-server / 腾讯实时 V1 通用引擎 / 腾讯实时 V2 大模型引擎），浮层驱动。

**最高频认知**：发送按键 / 文本 / 语音注入**都不在本进程完成**，而是经 IPC 调用 host 的 `IQuickSendService`。host 不实现对应方法 → 远程调用失败。改 IPC 接口**两边都要改**（host fork 在姐妹目录，IPC 代码在其 `release` 分支）。

## 1. 构建：debug / release 与 host 绑定

```bash
./gradlew assembleDebug        # 调试 APK：applicationIdSuffix=.debug，绑定 host debug（org.fcitx.fcitx5.android.debug）
./gradlew assembleRelease      # 发布 APK：绑定 host release（org.fcitx.fcitx5.android），需 release 签名
./gradlew downloadSherpaAar    # 单独下 Sherpa-ONNX AAR 到 libs/（构建期会自动跑）
./gradlew clean
```

- **JDK 17+** 运行 Gradle（AGP 9 / Gradle 9 要求）；字节码目标 Java 11。
- 产物 `build/outputs/apk/{debug,release}/`，按 ABI 拆 3 包（arm64-v8a / armeabi-v7a / x86_64），**无 universal 包**。
- `src/test` 有腾讯 ASR 客户端签名单测（`TencentV2SigningTest` / `TencentAsrV1SigningTest`，纯 JVM）；无 instrumented 测试（`src/androidTest`）。

> ⚠️ **坑 1（最关键）——签名一致**：插件经 `protectionLevel="signature"` 的 IPC 权限绑定 host，**双方必须同一签名证书**。debug 双方都用标准 Android debug keystore；release 用与 host 一致的 keystore（配置在 `local.properties` 的 `signing.*` 或 `SIGNING_*` 环境变量）。签名不一致 → 绑定失败 → 所有发送/注入都不工作。
> ⚠️ **坑 2——镜像源本地/CI 不一致**：仓库内 `settings.gradle.kts` 前置阿里云镜像、`gradle-wrapper.properties` 用腾讯云 gradle 分发镜像（为被墙环境）。**CI 会用 `sed` 把它们改回官方源**。改仓库源时**两处都要同步**。
> ⚠️ **坑 3——Sherpa AAR 不入库**：`libs/*.aar` 被 gitignore（~40MB），构建期由 `downloadSherpaAar` 从 HuggingFace 拉（带 `hf-mirror.com` 兜底）。本地被墙时手动放 AAR 或给 Gradle 配代理（`gradle.properties` 的 `https.proxyHost/Port`）。
> 💡 **版本号由 git tag 决定**（不在代码里维护）：`versionName` = 最近 tag 去 `v` 前缀；`versionCode` = `9_000_000 + major*100_000 + minor*1_000 + patch`。环境变量 `PLUGIN_VERSION`/`PLUGIN_VERSION_CODE` 可覆盖（CI 用）。发版=打 tag，无需改文件。

→ 构建/签名/CI/版本号/AAR/镜像源完整细节见 [build-and-release.md](build-and-release.md)。

## 2. IPC 与 host 联调（最高频踩坑源）

本插件能做的只是「发起 IPC 调用」；真正发送按键 / 写输入框的是 host 的 `IQuickSendService`。

- **接口方法**（全由 host 实现）：`commitText(text,cursor)` / `setComposingText(text)` / `finishComposingText()` / `sendKeyDownUpKey(keyCode,metaState)` / `sendKeyCombination(keyCode,alt,ctrl,shift,meta)` / `register|unregisterInputWindowStateListener`。
- **host 仓库**：fcitx5-android 的 fork，在姐妹目录（详见 [CLAUDE.md](../CLAUDE.md)「签名一致性」与 [README.md](../README.md)）；IPC 实现在其 **`release` 分支**。**改接口两边同步**。

> ⚠️ **坑 1——AIDL 同名同包铁律**：共享接口 `IQuickSendService` / `IInputWindowStateListener` 的 AIDL 在插件 `src/main/aidl/org/fcitx/fcitx5/android/common/ipc/`，包名/接口名/方法签名**必须与 host 侧完全一致**——binder 按 descriptor 匹配，任何不一致都会导致 `Stub.asInterface` 拿不到代理或调用失败。
> ⚠️ **坑 2——三路独立绑定**：`MainService` 被 host bind 时**反向绑定** host 并存入 `RemoteServiceHolder`；但 `QuickSendOverlayService` 和 `VoiceOverlayService` **各自独立 `bindService`**，不依赖 `RemoteServiceHolder`。原因：插件 APK 更新后 host 可能尚未重连 `MainService`，悬浮按钮/语音仍要能用各自的连接。改动时不要把三路合并。
> ⚠️ **坑 3——host 未实现方法时的降级**：`QuickSendExecutor` 在 `remote == null` 时返回 `false` + warning；调用抛异常时 `try/catch` 返回 `false`（不崩溃）。语音侧 `VoiceController` 的注入用 `runCatching` + warning log。**发送失败默认静默**——调试「点了没反应」先查 host 是否实现该方法、签名是否一致、`RemoteServiceHolder.service` 是否非空。

→ 双向绑定机制、组件职责、数据流见 [architecture.md](architecture.md)。

## 3. 语音：高频坑

- ⚠️ **网络 IO 禁压主线程**：任何远端识别器的 HTTP 调用（OkHttp `execute()` 等同步阻塞操作）**必须**包裹 `withContext(Dispatchers.IO)`，绝不能在主线程执行。Android StrictMode 直接抛 `NetworkOnMainThreadException`。GlmAsrRecognizer `uploadAndParse()` 已踩过此坑——stop() 是 suspend 函数但调用方默认在主线程协程执行，若不在内部切 IO 调度器则崩溃。
- ⚠️ **native 线程铁律（防 SIGSEGV）**：`SherpaRecognizer` 的所有原生对象（stream/AudioRecord）只在唯一的 `nativeThread` 上创建/使用/释放；`stop/cancel/releaseNow` 仅翻转 `@Volatile` 标志并 `join` 该线程，**绝不跨线程直接接触原生对象**。违反会在 `acceptWaveform` 处 native SIGSEGV（历史教训见 [tech-debt.md](tech-debt.md)）。
- ⚠️ **切换模式的初始化真空期**：浮层顶部「强制本地」开关切换本地/网络时，`teardownCurrentController()` 后到新 controller 接管前，UI 不再被 state 驱动。**必须在 teardown 后立即 `updateUi(VoiceUiState.Initializing)`**，否则状态冻结在切换前的 `[N]正在聆听`（实际网络已断、本地在加载）。见 [tech-debt.md](tech-debt.md)。
- **模型首次加载数秒**（`SherpaModelHolder.getOrLoad`，进程级单例常驻内存）；初始启动经 `VoiceController.start()` 内部加载，`Initializing` 状态天然覆盖加载期。
- **远端失败回退策略**：远端为**多后端优先级链**（`enable && tested`，按存储顺序）。链未耗尽时当前后端失败即试下一个；链耗尽后远端鉴权失败（`RemoteAuth`，含腾讯 4002/4003/4004/4005）/ 满载（`RemoteOverload`，腾讯 4006）**不静默回退本地**，明确提示 + Toast；仅 `Generic`（网络不通等）自动回退本地（`[NL]`，红色 N）。用户可随时点顶部「强制本地」手动切本地。
- **模型下载被墙**：设置页改模型 base URL（切镜像）或配代理 URI（prefs `voice_proxy_uri`，如 `http://127.0.0.1:7890`、`socks5://host:1080`）。识别本身本地、不走网络。
- **远端多后端配置**：主菜单「远端语音识别」→ Compose 设置页（`RemoteAsrSettingsActivity`），支持 streaming-asr-server / tencent-asr-v1（通用引擎，默认 `16k_zh`）/ tencent-asr-v2（仅大模型引擎）三类，列表「启用在前 + 长按拖拽排序」，底部抽屉编辑 + 单后端测试（说「测试」回写 `tested`）。V1/V2 同址同签名，公共字段抽到 `TencentAsrBackend` 接口。配置存 `voice_remote_backends`（JSON 数组），旧的 `voice_remote_*` 三键已丢弃不迁移。**⚠️ 改腾讯识别器务必守「Final = 会话结束」铁律**：稳态句只累积（`appendStable`）+ Partial，会话结束才一次 `markFinal`，否则首句终止会话 + 重复提交。

→ 语音管线、状态机、模型管理、识别参数完整细节见 [voice-subsystem.md](voice-subsystem.md)。

## 4. 数据层与发送链路速查

- 实体 `QuickSendEntry`（表 `quicksend`）：`id/label/segments/sendMode/useCount/...`；`sendMode`：`COMBINATION=0`（组合键一起发）/ `SEQUENCE=1`（逐键/逐字符顺序发）。
- `segments: List<ContentSegment(type, content)>`：`TYPE_TEXT=0` / `TYPE_KEY=1`（大写规范化键名如 `CTRL`）；经 `kotlinx.serialization` JSON 存库（`QuickSendConverters`）。
- **发送链路**：`entry.segments → SendActionBuilder.build()`（纯算法）→ `QuickSendExecutor.execute()` → `IQuickSendService` IPC → 成功后 `incrementUse`。
- **上限 500 条**，`QuickSendManager` 暴露 `StateFlow<List<QuickSendEntry>>`，写后 `reload()`。
- **键名 ↔ KEYCODE 全表**在 `KeyNameMapping.kt` 代码内（含修饰键/控制键/导航键/功能键/符号键/数字键盘/字母数字七大类与别名），查表直接看该文件。

→ 数据层与发送链路完整图见 [architecture.md](architecture.md)。

## 5. 日志与调试

- `VoiceLog` / `AppLog` 落**应用专用外部目录**文件（`VoiceLog` 2MB 自动轮转），语音设置页右上角 → 调试日志设置页可清空 / 分享（经 `FileProvider`）、开 `LOG_DEBUG_ENABLED`（同时落盘 + logcat，默认关，仅 WARN+）。
- **logcat tag 速查**（非穷尽）：`QuickSendMainService`（绑定）/ `QuickSendExecutor`（发送）/ `VoiceCtrl`（语音编排）/ `SherpaRec`（本地识别）/ `ModelHolder`（模型加载）/ `RemoteASR`（streaming-asr-server 识别）/ `TencentASRv1`（腾讯 V1 识别）/ `TencentASR`（腾讯 V2 识别）/ `GlmASR`（智谱 GLM ASR 识别）/ `VoiceOverlay`（语音浮层）/ `VoiceModel`（模型下载）。
- 联调「发送没反应」：先 logcat 过 `QuickSendExecutor`/`QuickSendMainService` 看 `Remote service not connected` / `Send failed`，再排查 host 实现 / 签名 / 三路绑定。

## 6. 协作规范（AI 写代码 / 文档时遵守）

- **代码风格**：注释用**中文**；协程 + `StateFlow` 驱动 UI；跨进程序列化用 `kotlinx.serialization`；IPC 调用切 `Dispatchers.IO`（host 端可能派发到 IMS 主线程阻塞）。
- **技术栈约束**：Kotlin `2.2.x` / AGP `9.x` / Gradle `9.x` / KSP / Room；**JDK 17+** 运行 Gradle，字节码 Java 11。**所有页面与弹窗用 Jetpack Compose + Material3**（`org.jetbrains.kotlin.plugin.compose` 插件 + Compose BOM），共用 `ui/theme/QuickSendTheme`（随系统深色模式）与 `ui/components/`（`QuickSendTopBar` 图标返回、`SettingSwitchRow`、`SettingTextFieldRow`、`SectionHeader`、`HelpIconButton`）；新增页面照此模板（`ComponentActivity` + `setContent { QuickSendTheme { XxxScreen(onBack={finish()}) } }`）。仅两个悬浮模块仍是编程式 View（WindowManager overlay，非页面）。无 XML 布局、无 ViewBinding、无 instrumentation 测试。
- **文档单一权威**：每个技术主题一处权威，其它文档**交叉引用**而非复制；改「清单型」内容必须同步对应 docs——新增/修复坑 → [tech-debt.md](tech-debt.md)；IPC/组件变更 → [architecture.md](architecture.md)；语音变更 → [voice-subsystem.md](voice-subsystem.md)；构建/签名/CI 变更 → [build-and-release.md](build-and-release.md)。
- **改动前先确认**：任何改文件/系统的操作先说明意图，获同意再动手（见 [CLAUDE.md](../CLAUDE.md)）。
