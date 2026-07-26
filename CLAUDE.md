# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

fcitx5-android 的**快捷发送独立插件 APK**（参考 `fcitx5-android-clipboard-helper-plugin` 的插件协议）。两个功能支柱：

1. **QuickSend**：发送预设的按键组合 / 文本（如 `Ctrl+Shift+Del`、`Shift+Tab`），条目存 Room。
2. **语音输入**：基于 Sherpa-ONNX 的本地流式中文识别，运行时下载模型，浮层驱动。

插件不是主项目模块，通过 AIDL IPC 与 fcitx5-android **主程序**（host）通信。**关键依赖**：发送按键/文本/语音注入都需要 host 在 `IQuickSendService` 中实现对应方法 —— 这部分在 host 侧（本仓库的姐妹目录 `/data/www-data/code/fcitx5-android` 的 fork，IPC 代码在其 `release` 分支）。本仓库只含插件侧。

## 常用命令

```bash
# 构建前确保：local.properties 指向 Android SDK（sdk.dir=...），且 libs/ 有 Sherpa AAR
./gradlew assembleDebug      # 调试 APK，applicationIdSuffix=.debug，绑定 host debug
./gradlew assembleRelease    # 发布 APK，绑定 host release（需 release 签名）
./gradlew test               # 跑单元测试（当前仓库无测试源码，仅声明了 junit 依赖）
./gradlew downloadSherpaAar  # 单独下载 Sherpa-ONNX AAR 到 libs/（构建期会自动跑）
./gradlew clean
```

- **JDK**：用 JDK 17+ 运行 Gradle（AGP 9 / Gradle 9 要求）；`compileOptions` 字节码目标为 Java 11。
- **单测过滤**：`./gradlew test --tests "org.fcitx....ClassName.methodName"`（有测试时）。
- 无 instrumented 测试（`src/androidTest`）、无 `src/test` 源码。
- 产物：`build/outputs/apk/{debug,release}/`。按 ABI 拆 3 个包（arm64-v8a / armeabi-v7a / x86_64），无 universal 包。

## 架构（全貌）

深入版见 [`docs/architecture.md`](docs/architecture.md)。要点：

- **双向绑定**：host 绑定插件 `MainService`（`${fcitxAppId}.plugin.SERVICE`）；`MainService.onBind` 时插件**反向绑定** host 的 `IQuickSendService`（`${fcitxAppId}.quicksend.IPC`，`permission.IPC` 为 signature 级），存入 `RemoteServiceHolder`。
- **悬浮窗/语音服务各自独立绑定 host**（不依赖 `RemoteServiceHolder`），以便 IME 未主动加载插件时仍能工作。见 `QuickSendOverlayService` / `VoiceOverlayService`。
- **数据层**：Room（`data/db/`，表 `quicksend`，上限 500 条全量加载）；`List<ContentSegment>` 经 `kotlinx.serialization` JSON 存库。`QuickSendManager` 单例暴露 `StateFlow`。
- **发送链路**：`ContentSegment[]` → `SendActionBuilder`（按 `sendMode` COMBINATION/SEQUENCE 合并）→ `QuickSendExecutor` → `IQuickSendService` IPC（`commitText` / `sendKeyDownUpKey` / `sendKeyCombination`）。
- **可见性驱动**：`QuickSendOverlayService` 注册 `IInputWindowStateListener`，host 在软键盘显隐时回调，决定悬浮按钮显隐（不轮询）。
- 包结构：组件在 `org.fcitx.fcitx5.android.plugin.quicksend`；AIDL 共享接口在 `org.fcitx.fcitx5.android.common.ipc`（**必须与 host 侧同名同包**，否则 binder 不互通）。

## 语音子系统

深入版见 [`docs/voice-subsystem.md`](docs/voice-subsystem.md)。要点：

- `VoiceOverlayService`（前台服务，`foregroundServiceType=microphone`）由 host 语音按钮 `startForegroundService(START)` 启动 → `VoiceController` → `SherpaRecognizer`（本地流式 ASR）。
- **流式注入**：partial 经 `setComposingText` 写入输入框组合区；final 经可选 `TextRefiner`（当前 `NoOpRefiner`）后 `commitText`。
- ⚠️ **native 线程铁律**：`SherpaRecognizer` 中所有 Sherpa 原生对象（recognizer/stream/AudioRecord）只在唯一的 `nativeThread` 上创建、使用、释放；`stop/cancel/releaseNow` 仅翻转 volatile 标志并 `join` 该线程，**绝不跨线程直接接触原生对象**。违反会导致 `acceptWaveform` 处 native SIGSEGV（use-after-free，已踩过坑）。
- 模型运行时下载（`VoiceModelManager`，默认 HuggingFace，可改镜像/代理）。扩展点 `RecognizerProvider`（在线 ASR）/`TextRefiner`（大模型润色）目前是占位接口。

## 关键约束与坑

详见 [`docs/build-and-release.md`](docs/build-and-release.md)。

- **签名一致**：插件通过 signature 级 IPC 权限绑定 host，**双方必须用相同签名证书**。debug：双方都用标准 Android debug keystore；release：用与 host 一致的 release keystore。签名配置来自 `local.properties` 的 `signing.*` 或 `SIGNING_*` 环境变量。
- **镜像源**：`settings.gradle.kts` 前置阿里云镜像、`gradle-wrapper.properties` 用腾讯云 gradle 分发镜像。**CI 会用 `sed` 把这些改回官方源**，本地依赖这些镜像（被墙环境）。改仓库源时两处都要看。
- **Sherpa AAR 不入库**：`libs/*.aar` 被 gitignore（~40MB），构建期由 `downloadSherpaAar` 任务从 HF 拉（带 `hf-mirror.com` 兜底）。本地被墙时可手动放 AAR 或给 Gradle 配代理（`gradle.properties` 的 `https.proxyHost/Port`）。
- **版本号**：`version.properties`（`versionName`/`versionCode`）← 可被环境变量 `PLUGIN_VERSION`/`PLUGIN_VERSION_CODE` 或 gitignore 的 `version.local.properties` 覆盖。CI release 用 git tag 作 `PLUGIN_VERSION`。

## 文档索引

| 文档 | 内容 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 组件全图、IPC 双向绑定、数据流、各 service 职责 |
| [docs/voice-subsystem.md](docs/voice-subsystem.md) | 语音管线、native 线程模型、模型下载与代理 |
| [docs/build-and-release.md](docs/build-and-release.md) | 构建/签名/CI/版本号/Sherpa AAR/镜像源细节 |
| docs/fcitx5-plugin-quicksend/requirements-analysis.md | 原始需求分析（设计期） |
| docs/fcitx5-plugin-quicksend/data-model-proposal.md | 数据模型与特殊键映射表（type=1 键名 ↔ KEYCODE 全表，查表用） |
| docs/fcitx5-plugin-quicksend/implementation-guide.md | 原始实现指南（设计期，部分路径指向 host） |
