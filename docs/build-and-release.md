# 构建与发布

单模块 Gradle 项目（`com.android.application`，无子模块）。AGP `9.2.0`、Kotlin `2.2.10`、KSP `2.2.10-2.0.2`、Gradle `9.4.1`、Room `2.7.1`、Sherpa-ONNX AAR `1.12.21`。`compileSdk=35`、`minSdk=24`、`targetSdk=35`，字节码目标 Java 11。

## JDK

用 **JDK 17+** 运行 Gradle（AGP 9 / Gradle 9 要求；CI 用 temurin 17）。`compileOptions` 与 `kotlin.compilerOptions.jvmTarget` 均为 `JVM_11`。

## 本地首次配置

1. `echo "sdk.dir=/path/to/Android/Sdk" > local.properties`（`local.properties` 已 gitignore）。
2. 确保 `libs/sherpa-onnx-1.12.21.aar` 存在 —— 构建期 `downloadSherpaAar` 会自动拉取；被墙时可手动放或给 Gradle 配代理。
3. release 签名：在 `local.properties` 写 `signing.storeFile / signing.storePassword / signing.keyAlias / signing.keyPassword`（或用同名 `SIGNING_*` 环境变量）。**证书必须与 host 一致**（见下）。

## 签名一致性（最关键的硬约束）

插件经 `protectionLevel="signature"` 的 IPC 权限（`${fcitxAppId}.permission.IPC`）绑定 host，**双方必须用同一签名证书**：

- **debug**：双方都用标准 Android debug keystore 即可（插件 debug 带 `applicationIdSuffix=.debug`，绑定 `org.fcitx.fcitx5.android.debug`）。
- **release**：用与 host 一致的 release keystore。host 侧在本仓库姐妹目录 `/data/www-data/code/fcitx5-android` 的 fork（IPC 代码在其 `release` 分支）。

`build.gradle.kts` 的 `signingConfigs.release`：`storeType=PKCS12`，`keyAlias` 默认 `fcitx5-android-quicksend-plugin`；优先取环境变量，回退 `local.properties`。

> `generate-keystore.sh` 是交互式生成 keystore + GitHub secrets 信息的辅助脚本（注意其内部输出路径 `app/release.keystore` 是从早期布局沿用的，实际签名路径以 `build.gradle.kts` 配置为准）。

## 镜像源（本地 vs CI 不一致）

| 位置 | 本地（仓库内） | CI 改成 |
|------|----------------|---------|
| `settings.gradle.kts` | 前置阿里云镜像（`maven.aliyun.com/repository/{gradle-plugin,google,public}`） | `sed '/maven.aliyun.com/d'` 删除，回到官方源 |
| `gradle/wrapper/gradle-wrapper.properties` | 腾讯云 gradle 分发镜像 `mirrors.cloud.tencent.com/gradle` | `sed` 改回 `services.gradle.org/distributions` |

CI（`.github/workflows/build.yml`）的 `Replace mirror sources` 步骤做这两处替换。**改动仓库源时两处都要同步**，否则 CI 可能连不上而被墙环境本地依赖镜像。

本地被墙时：可给 Gradle 设代理（`gradle.properties` 加 `https.proxyHost`/`https.proxyPort`，参考用户记忆中的本地代理 `127.0.0.1:7890`）。

## Sherpa-ONNX AAR（不入库）

- `libs/*.aar` 被 gitignore（~40MB）。
- `downloadSherpaAar` 任务：HuggingFace 主源 + `hf-mirror.com` 兜底，各 3 次重试；>1MB 才算成功。`fileTree("libs") { builtBy("downloadSherpaAar") }` 声明产出，所有 `compile*Kotlin` 任务自动依赖它。
- CI 按 Sherpa 版本缓存 `libs/`（`actions/cache`，key 含 `sherpaAarVersion`），避免每次重下。

## ABI 拆分

`splits.abi` 开启，输出 `arm64-v8a` / `armeabi-v7a` / `x86_64` 三个独立 APK，**无 universal 包**（Sherpa native 库随 ABI 拆开，控制单包体积）。

## 版本号

**版本号不在代码里维护，发布时直接由 git tag 决定。** 仓库不再有 `version.properties`。

- `versionName` = 最近的 git tag（去掉前缀 `v`），即 `git describe --tags --abbrev=0`，如 tag `v0.9.0` → `0.9.0`；无 tag 时兜底 `0.0.0-dev`。
- `versionCode` = 由 tag 的语义化版本换算：`9_000_000 + major*100_000 + minor*1_000 + patch`（基址 9_000_000 保证单调且高于历史手工码 1_000_0xx）。如 `0.9.0` → `9009000`、`0.8.18` → `9008018`。
- 环境变量覆盖（CI 用）：`PLUGIN_VERSION`（versionName）、`PLUGIN_VERSION_CODE`（versionCode）优先级最高。
- 解析失败兜底：`1_000_000 + 提交数`。

实现见 `build.gradle.kts` 顶部 `gitTagName()` / `tagToVersionCode()` 等。发版流程：打 tag（如 `git tag v0.9.0 && git push origin v0.9.0`）→ CI 在该 tag 上构建，versionName/versionCode 即由 tag 决定，无需手动改任何文件。


## CI（`.github/workflows/build.yml`）

- **debug** job：`push` 到 `main`/`dev` 触发；JDK 17 → 换官方源 → 恢复 Sherpa 缓存 → `assembleDebug` → 上传 APK artifact（保留 30 天）。
- **release** job：打 `v*` tag 触发；从 `secrets.KEYSTORE_BASE64` 解出 keystore 到 `$RUNNER_TEMP`，设 `SIGNING_*` 环境变量 → `assembleRelease` → 重命名为 `QuickSendPlugin-<tag>-release.apk` → 上传 artifact（90 天）+ `softprops/action-gh-release` 建 GitHub Release（`generate_release_notes: true`）。`if: always()` 清理 keystore。

## 常见命令速查

```bash
./gradlew assembleDebug                         # 调试 APK（绑定 host debug）
./gradlew assembleRelease                       # 发布 APK（绑定 host release）
./gradlew downloadSherpaAar                     # 单独下 Sherpa AAR
./gradlew test                                  # 单元测试（腾讯 ASR 客户端签名）
./gradlew test --tests "全限定类名.方法名"        # 跑单个测试（有测试时）
./gradlew clean
```

产物：`build/outputs/apk/{debug,release}/*.apk`。
