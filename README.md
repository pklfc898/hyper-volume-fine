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
| 当前版本 | 4.0.2（versionCode 31） |
| minSdk / targetSdk | 29 / 37 |
| Xposed 接口 | libxposed API 102 |
| 作用域 | `system` |

模块在 system_server 中 hook：

- `AudioService` 构造函数（改写 `MAX_STREAM_VOLUME`，只改数组元素，不替换 static final 引用）
- `SystemProperties.getInt`（劫持 `ro.config.media_vol_steps`）
- `AudioService$VolumeStreamState.adjustIndex` / `updateIndexFactors`
- `AudioSystemAdapter.setStreamVolumeIndexAS`（仅调试日志）

## 版本变更（3.8 → 4.0）

### 3.9 · 性能与稳定性

- **配置读取零查库**：配置改为不可变快照，启动时加载一次，后续由内容观察者 / 广播写入触发后台刷新；Hook 回调只读内存，不再因缓存过期而同步查询 Settings。（`VolumeFineModule`）
- **全局属性 Hook 收敛**：`SystemProperties.getInt` 仅在启动窗口保留，AudioService 构造完成后主动卸载，避免运行期全系统属性调用都过 Hook。（`VolumeFineModule`）
- **调试 Hook 按需安装**：`updateIndexFactors` / `setStreamVolumeIndexAS` 两个日志 Hook 仅在 debug 开启时挂载。（`VolumeFineModule`）
- **广播鉴权加固**：配置广播改用预共享令牌校验，发送方 UID 作兼容回退，防止伪造写入。（`VolumeFineModule`、`ConfigContract`、`SettingsActivity`）

### 3.9.1 · 修复

- 修复未授予「修改系统设置」权限时保存配置失败的问题：模块 UID 增加 PackageManager 查询兜底，避免合法广播被误拒。

### 4.0.0 · 移除滑条吸附

- 移除「滑条吸附」功能及其 Hook、配置项、设置项与文档描述。设置页参数区精简为 4 项；若系统中残留 `volume_fine_snap` 键，新版不再读取，无影响。（`VolumeFineModule`、`ConfigContract`、`SettingsActivity`）

### 4.0.2 · 修复四处启动期与边界缺陷

- **档位数重算**：`applyStepCount()` 改为拿到真实配置后才应用并置位；配置迟到时由补偿线程补算档位，修复档位数停留在默认值。（`VolumeFineModule`）
- **属性 Hook 卸载时机**：3 秒卸载计时移到 AudioService 构造完成之后，避免构造超时导致「数组已改、属性仍是原生值」的不一致。（`VolumeFineModule`）
- **档位未知兜底**：当前设备与默认设备的索引都查不到时不再用 0 充当当前档位，改为放行原生逻辑，避免误判细调区、步进被放大。（`VolumeFineModule`）
- **回滚比例**：回滚改用实际写入的档位值作分母，而非配置值，修复改过 steps 后关闭模块时缩放比例错误。（`VolumeFineModule`）

### 4.0.1 · 修复开机配置回退默认

- 修复部分场景（如更新 root 方案后重启）中，模块注入早于 SettingsProvider 就绪，启动期空读被固化为默认配置，导致音量步进停留在默认值、需手动进设置页保存才恢复的问题。（`VolumeFineModule`）
- 现在区分「Settings 未就绪」与「未配置」：前者不再固化默认值，并在观察者就绪及启动后分时补偿重载配置，开机后自动应用用户设置。

### 对用户的影响

- **使用方式**：档位数、步进、细调范围、开关的操作与生效规则均不变；仅去掉滑条吸附这一低频功能。
- **流畅度 / 耗电**：音量调节与系统属性读取不再触发同步查库或常驻全局 Hook，system_server 侧开销下降。
- **升级路径**：3.9 / 3.9.1 → 4.0.0 签名相同，可直接覆盖；3.8 → 4.0.0 签名不同，需卸载重装并在 LSPosed 重新勾选「系统」作用域，配置值不丢失。

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

仓库已自带 `keys/release.jks`（口令见「签名」章节），直接构建即可复现 Release 签名；仅当密钥缺失时脚本才用 `KS_PASS` 重新生成，那会产生新签名、无法覆盖已装版本。

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

仓库与 Release 均包含签名密钥 `keys/release.jks`（口令 `hypervolumenice`），用于复现构建与覆盖升级。3.9 起各版本使用同一签名，可直接覆盖；3.8 及更早版本使用另一套签名，升级需卸载重装。

> 该密钥为公开私钥，任何人都可用它签名同包名 APK，请勿用于本模块以外的用途。若需独占签名身份，请改用自有密钥并自行承担升级时的签名变更。

## 免责声明

本项目修改系统音量行为，仅供学习与个人使用。使用前请确认你能承担刷机/系统异常的风险。
