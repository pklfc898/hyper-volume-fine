# 澎湃音阶微调 · Hyper Volume Fine

一个 LSPosed 模块：把系统音量从「原生 N 档」拆成更细的步进，解决低音量区一格太大的问题。

- 目标平台：Android 17（API 37）
- 最低支持：Android 10（API 29）
- 框架：libxposed API 102，需要 LSPosed ≥ 2.1.0
- 作用域：**仅 `system`**（system_server）。从 3.7 起不再注入系统界面。

## 功能

- **自定义总档位数**：默认 60 档（可调 20–120），改完重启生效。
- **低音量 / 高音量分开设置步进**：低音量区默认 1 档/次（最细），超过「细调范围」后按 3 档/次走。
- **细调范围**：默认 12 档，决定从哪里开始加速。
- **设置页「确认更改」**：所有改动先进入本地草稿，一次性提交；支持直写与广播双通道，并回读校验、失败重试与 adb 兜底。
- **一键恢复默认**：默认值为 60 / 12 / 1 / 3。

## 兼容性

| 项 | 值 |
|---|---|
| 包名 | `com.hypervolumenice` |
| 当前版本 | 4.0.0（versionCode 29） |
| minSdk / targetSdk | 29 / 37 |
| Xposed 接口 | libxposed API 102 |
| 作用域 | `system` |

模块在 system_server 中 hook：

- `AudioService` 构造函数（改写 `MAX_STREAM_VOLUME`，只改数组元素，不替换 static final 引用）
- `SystemProperties.getInt`（劫持 `ro.config.media_vol_steps`）
- `AudioService$VolumeStreamState.adjustIndex` / `updateIndexFactors`
- `AudioSystemAdapter.setStreamVolumeIndexAS`（仅调试日志）

## 安装

1. 安装 APK。
2. 在 LSPosed 中启用「澎湃音阶微调」，作用域只勾选 **系统**。
3. 重启手机（档位数与开关状态在 AudioService 构造时读取）。
4. 打开应用，调整参数后点底部 **确认更改**。

> 停用模块前，请先在设置页关闭开关并保存，否则下次开机的音量会被截断到最大档。

## 构建

仓库不依赖 Gradle，使用 SDK 自带工具直接出包。

依赖：

- JDK 17+
- Android SDK：`platforms/android-37.0`、`build-tools/37.0.0`
- [libxposed](https://github.com/LSPosed/libxposed) API 102 的 `classes.jar`

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
export LIBXPOSED_JAR=/path/to/libxposed-api-102.jar
export KS_PASS=你的签名口令
bash build.sh
```

产物：`dist/hyper-volume-fine-<version>.apk`。

首次运行若 `keys/release.jks` 不存在，脚本会用 `KS_PASS` 生成一个；请妥善保管，后续升级必须使用同一密钥。

## 目录结构

```
AndroidManifest.xml
build.sh                     # aapt2 → javac → d8 → zipalign → apksigner
src/com/hypervolumenice/
  VolumeFineModule.java      # system_server 侧 hook 与配置
  SettingsActivity.java      # 设置页（草稿 + 确认更改）
  ConfigContract.java        # 键名、默认值、范围、生效方式
  M3Switch.java              # 自绘开关
res/                         # 颜色、卡片、矢量图标（自适应图标 v26/v33）
META-INF/xposed/             # module.prop / java_init.list / scope.list
```

## 配置存储

所有参数写在 `Settings.System` 的 `volume_fine_*` 键中，卸载应用不会丢失：

| 键 | 含义 | 默认 |
|---|---|---|
| `volume_fine_enabled` | 是否启用 | 1 |
| `volume_fine_steps` | 总档位数 | 60 |
| `volume_fine_threshold` | 细调范围（档） | 12 |
| `volume_fine_fine` | 低音量步进（档/次） | 1 |
| `volume_fine_coarse` | 高音量步进（档/次） | 3 |

## 已知限制

- 声音走 `STREAM_MUSIC`，铃声 / 闹钟 / 通知保持系统原生档位。
- 档位数与启用状态需要重启才能生效；其余参数保存后即时生效。
- 没有 SystemUI hook，因此不会在音量面板上叠加数字。

## 签名

仓库**不包含**签名密钥。Release 里的 APK 使用开发者本地密钥签名，直接重新构建会得到不同签名，无法覆盖安装；请用自己的 `keys/release.jks` 保持升级连续性。

## 免责声明

本项目修改系统音量行为，仅供学习与个人使用。使用前请确认你能承担刷机/系统异常的风险。
