# fcitx5-plugin-quicksend

fcitx5 安卓输入法的**快捷发送**独立插件。在输入法中快速发送预设的快捷键组合（如 `Ctrl+Shift+Del`、`Shift+Tab`）或文本句子。

> 这是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的**独立插件 APK**（参考 `fcitx5-android-clipboard-helper-plugin` 的插件协议），不是集成进主项目的模块。

## 功能

- 发送内容支持：快捷键组合、单键、文本句子、混合
- 两种发送模式：
  - **一起发送（COMBINATION）**：模拟组合键同时按下，适用于 `Ctrl+Shift+Del`
  - **单个发送（SEQUENCE）**：逐字符 / 逐键顺序发送，适用于文本
- 完整的条目管理（增删改）、按使用次数倒序排序、使用计数器
- 编辑器提供特殊键分组下拉（修饰键 / 控制键 / 导航键 / 功能键 / 符号键 / 数字键盘）
- 最大 500 条，全量加载

## 架构

作为独立插件 APK，通过 AIDL IPC 与 fcitx5-android 主程序通信：

```
quicksend 插件  ──bind──▶  fcitx5-android (IFcitxRemoteService)
                            ├─ commitText / 发送文本
                            └─ sendDownUpKeyEvents / 发送按键
```

数据层使用 Room 存储 `QuickSendEntry`，内容段 `ContentSegment` 用 `kotlinx.serialization` 序列化为 JSON。

> ⚠️ 主动发送按键 / 文本需要 fcitx5-android 主程序在 `IFcitxRemoteService` 中提供相应的 IPC 方法。详见 `docs/fcitx5-plugin-quicksend/`。

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

产物位于 `build/outputs/apk/`。

## 文档

需求与设计文档位于 [`docs/fcitx5-plugin-quicksend/`](docs/fcitx5-plugin-quicksend/)：

- `requirements-analysis.md` — 需求分析
- `data-model-proposal.md` — 数据模型与特殊键映射
- `implementation-guide.md` — 实现指南
