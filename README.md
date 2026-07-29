# fcitx5-plugin-quicksend

fcitx5 安卓输入法的独立插件 APK，提供**快捷发送**与**语音输入**两个功能：在输入法中快速发送预设的快捷键组合（如 `Ctrl+Shift+Del`、`Shift+Tab`）或文本句子；以及中文语音输入（默认本地离线识别，可配置远端 ASR 后端）。

> 这是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的**独立插件 APK**（参考 `fcitx5-android-clipboard-helper-plugin` 的插件协议），不是集成进主项目的模块。

## 功能

### 快捷发送

- 发送内容支持：快捷键组合、单键、文本句子、混合
- 两种发送模式：
  - **一起发送（COMBINATION）**：模拟组合键同时按下，适用于 `Ctrl+Shift+Del`
  - **单个发送（SEQUENCE）**：逐字符 / 逐键顺序发送，适用于文本
- 完整的条目管理（增删改）、按使用次数倒序排序、使用计数器
- 编辑器提供特殊键分组下拉（修饰键 / 控制键 / 导航键 / 功能键 / 符号键 / 数字键盘）
- 最大 500 条，全量加载
- 软键盘弹出时显示边缘悬浮按钮，点开即选即发；收起自动隐藏

### 语音输入

**本地识别**（默认，离线可用）：

- 基于 **Sherpa-ONNX** 的本地流式中文识别（Zipformer-transducer），无需联网
- 识别过程实时写入输入框组合区（下划线预览），完成后提交最终文本
- 模型运行时下载（默认 HuggingFace，可在设置页改镜像 / 代理），约十几 MB

**远端识别**（可选，多后端优先级链）：

- 支持多类后端按优先级链式尝试：自建 [streaming-asr-server](https://github.com/loveyu/streaming-asr-server)、腾讯云实时语音识别 **V1**（通用引擎 `16k_zh` 等）/ **V2**（大模型引擎）
- 启用的后端按顺序尝试，全部失败再回退本地；鉴权 / 满载类错误明确提示、不静默回退
- 每个后端可单独「测试」（对麦克风说「测试」，识别结果含「测试」即通过），未通过测试的不参与识别
- 每个后端可单独配置代理（http / socks5）；入口在主菜单「远端语音识别」

前台服务 + 麦克风类型，满足 Android 14 后台录音要求。

## 架构

作为独立插件 APK，通过 AIDL IPC 与 fcitx5-android 主程序通信。主程序绑定插件 `MainService` 后，插件反向绑定主程序的 `IQuickSendService`：

```
quicksend 插件  ──bind──▶  fcitx5-android (IQuickSendService)
                            ├─ commitText / setComposingText   发送文本、语音组合态
                            ├─ sendKeyDownUpKey                发送单键
                            └─ sendKeyCombination              发送组合键
```

- **快捷发送**：Room 存储 `QuickSendEntry`，内容段 `ContentSegment` 用 `kotlinx.serialization` 序列化为 JSON；悬浮窗 / 设置页选中条目后经上述 IPC 发送。
- **语音输入**：`VoiceOverlayService` 由主程序语音按钮启动 → 本地 Sherpa 流式识别 / 远端多后端链 → partial 经 `setComposingText`、final 经 `commitText` 注入输入框。

> ⚠️ 主动发送按键 / 文本 / 语音注入均需 fcitx5-android 主程序在 `IQuickSendService` 中实现相应方法（本仓库姐妹目录 `fcitx5-android` 的 fork，IPC 代码在其 `release` 分支）。详见 [`docs/architecture.md`](docs/architecture.md)、[`docs/voice-subsystem.md`](docs/voice-subsystem.md)。

## 签名要求

插件通过 `protectionLevel="signature"` 的 IPC 权限绑定主程序，因此**插件必须与 fcitx5-android 使用相同的签名证书**：

- 调试构建：双方都用标准 Android debug keystore 即可
- 发布构建：需用与主程序一致的 release keystore 签名

## 构建

```bash
# 1. 生成本地签名密钥（仅首次，交互式）
./generate-keystore.sh

# 2. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 3. 构建
./gradlew assembleDebug     # 调试版（绑定 fcitx5-android debug）
./gradlew assembleRelease   # 发布版（绑定 fcitx5-android release）
```

产物位于 `build/outputs/apk/`（按 ABI 拆分为 arm64-v8a / armeabi-v7a / x86_64 三个包）。

构建期会自动下载 Sherpa-ONNX AAR 到 `libs/`（被墙时可手动放置或配代理）；语音识别模型在 App 内首次使用时从「语音输入设置」页下载。

## 文档

实现与运维文档（`docs/`），**先看 [`ai-dev-playbook.md`](docs/ai-dev-playbook.md)** 获取高频操作速查与踩坑入口：

- [`ai-dev-playbook.md`](docs/ai-dev-playbook.md) — AI 协作开发操作手册（构建 / IPC 联调 / 语音坑 / 数据层 / 日志 / 规范速查）
- [`architecture.md`](docs/architecture.md) — 组件架构、IPC 双向绑定、数据流
- [`voice-subsystem.md`](docs/voice-subsystem.md) — 语音管线、native 线程铁律、本地 + 远端多后端 ASR、模型下载与代理
- [`build-and-release.md`](docs/build-and-release.md) — 构建 / 签名 / CI / 版本号 / 镜像源细节
- [`tech-debt.md`](docs/tech-debt.md) — 历史坑与设计权衡活文档
