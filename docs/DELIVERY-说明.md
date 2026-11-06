# GlassMic · MiuiX 改版 + 麦克风混音 交付说明

生成时间：2026-11-01　基线：`lm060719/io.mo.glassmic` v1.4.8 (versionCode 27)

---

## 一、包里有什么

| 文件 | 说明 |
|---|---|
| `GlassMic/` | 改版后的完整工程源码，可直接 `./gradlew assembleDebug` |
| `glassmic-miuix-mix.patch` | 相对上游 v1.4.8 的全量变更（26 个文件），可套到你自己的仓库 |
| `README-DELIVERY.md` | 本说明 |
| `docs/2026-10-31_MiuiX改版与麦克风混音-design.md` | 架构设计、决策依据、验证记录与已知边界 |

已排除：`.git/`、`*/build/`、`.gradle/`、`local.properties`（那是构建机上的 SDK 路径，你需要自己生成）。

## 二、三种用法

**A. 直接构建整包（推荐先用这个）**

```bash
cd GlassMic
echo "sdk.dir=/你的/Android/sdk" > local.properties
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

**B. 已有上游仓库，只想套改动**

```bash
cd 你的GlassMic仓库          # 需停在 upstream v1.4.8
git apply --check glassmic-miuix-mix.patch   # 先预检
git apply glassmic-miuix-mix.patch
```

**C. 只想看改了哪些文件**

```
新增 4 个：
  app/.../audio/MicCaptureMixer.kt          应用侧采集 + 环形缓冲 + 混音叠加
  xposed/.../MixPcm.kt                      目标进程内混音算术（4 种缓冲格式）
  app/.../ui/common/Miuix.kt                HyperOS 通栏底栏 / 分组标题 / 大标题
  app/.../ui/common/MicPermission.kt        RECORD_AUDIO 运行时授权封装

修改 22 个：
  app/src/main/proto/app_config.proto       新增 MicMix + MixEngine（字段 25，默认关闭）
  core/.../model/Enums.kt                   MixEngine、MicMixMode
  core/.../model/RuntimeState.kt            混音运行态 + ConfigSnapshot 混音字段
  app/.../audio/SharedPcmPublisher.kt       效果链后调用 mixInto()
  app/.../data/audio/PlaybackController.kt  三态切换、混音参数写入
  app/.../data/config/ConfigStore.kt        快照转换 + 共享偏好同步
  app/.../data/runtime/PermissionChecker.kt mic 状态位、checkMic、永久拒绝判定
  app/.../data/runtime/RuntimeStateHolder.kt setMicMixing()
  app/.../provider/RuntimeProvider.kt       /resolve 附带 mix 列下发
  xposed/.../XBridge.kt                     解析并缓存 MixDecision
  xposed/.../AudioRecordHook.kt             四条 read 路径的 HOOK_SIDE 混音
  app/.../MainActivity.kt                   底部四栏 + MiuiX 通栏底栏
  app/.../ui/theme/GlassTokens.kt           HyperOS 配色 / 圆角 / inset
  app/.../ui/common/GlassKit.kt             卡片、开关 51×31、分段控件、设置行 60dp、大标题 34sp
  app/.../ui/home/HomeScreen.kt             工作模式三态卡片 + 授权交互
  app/.../ui/home/HomeViewModel.kt          三态状态推导、授权回调、降级提示
  app/.../ui/settings/SettingsScreen.kt     「麦克风混音」分区（含引擎 A/B 切换）
  app/.../ui/settings/SettingsViewModel.kt  setMix* 系列写入 + 采集状态读取
  app/.../ui/scope/ScopeScreen.kt           asTab 支持（从二级页提升为标签页）
  app/.../ui/onboarding/OnboardingFlow.kt   引导页补麦克风权限行
  app/src/main/res/values/strings.xml       全部新文案（简中）
  app/src/main/res/values-en/strings.xml    全部新文案（英文）
```

## 三、构建前置

- JDK 17（工程要求 `jvmToolchain(17)`；Gradle daemon 建议跑在 17，实测 JDK 21 作 daemon JVM 会出问题）
- Android SDK Platform **36**（app 模块 `compileSdk = 36`）、Build-Tools 36.0.0
- NDK **26.1.10909125** + CMake **3.22.1**（xposed 模块 native AAudio hook 需要）
- Gradle 9.5.0（wrapper 指定；官方分发站慢，可换镜像）

**国内网络拉不到 `maven.google.com` 时的解法**（我这边实测可用）：把镜像写到 Gradle 用户目录的 init 脚本里，不污染仓库。

`~/.gradle/init.d/mirrors.init.gradle.kts`：

```kotlin
val GOOGLE  = "https://maven.aliyun.com/repository/google"
val PUBLIC  = "https://maven.aliyun.com/repository/public"
val GPLUGIN = "https://maven.aliyun.com/repository/gradle-plugin"
val XPOSED  = "https://api.xposed.info/"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.mirrors(xposed: Boolean) {
    val keep = filter { it.toString().contains("api.xposed.info") }.toSet()
    clear()
    maven { setUrl(GOOGLE) }; maven { setUrl(PUBLIC) }; maven { setUrl(GPLUGIN) }
    if (xposed || keep.isNotEmpty()) maven { setUrl(XPOSED) }
    gradlePluginPortal(); google(); mavenCentral()
}

beforeSettings {
    pluginManagement.repositories.mirrors(false)
    dependencyResolutionManagement.repositories.mirrors(true)
}
settingsEvaluated {
    pluginManagement.repositories.mirrors(false)
    dependencyResolutionManagement.repositories.mirrors(true)
}
```

## 四、务必知道的验证状态

**已用真实编译器验证过：**

- `core` 模块完整编译通过（18 个 class，含新增 `MixEngine` 与 `MicMixMode`）
- `xposed` 模块完整编译通过：android-36 `android.jar` + libxposed API 101 + legacy 82 + shadowhook 1.0.10，**0 错误、46 个 class**。过程中查出并修好 2 个真实类型错误（`clamp()` 缺 Float 重载）
- `app_config.proto` 经 protoc 3.25.5 校验通过，生成的 `getMicMix/setMicMix`、`setEnabled/setEngine/setMusicGain/setMicGain/setDucking` 与代码调用点逐一对齐

**未经验证：`app` 模块的 Kotlin 源码。** 交付环境无法运行 Gradle（容器内核限制 + Google Maven 不可达），这部分只有人工审查。首次编译预计需要复查：

1. `HomeScreen.kt` 的 `MicModeCard` 参数名与 `Segmented` / `PrimaryButton` / `SoftButton` 实参是否匹配
2. `SettingsScreen.kt` 新增分区里 `vm::setMix*` 与 `StackedRow` 重载
3. `MainActivity.kt` 因删除浮动底栏而残留的未使用 import（只报 warning）
4. 各文件新加 import 是否有遗漏或重复（重复 import 在 Kotlin 里只是 warning）

## 五、功能验收清单（静态检查测不出混音，必须真机）

- **APP_SIDE**：微信语音输入页 → 播放音频 + 对麦克风说话 → 确认两者同时被录入
- **HOOK_SIDE**：同上；先确认目标 App 走哪条 read 重载。注意 native AAudio / OpenSL 在此引擎下**仍是纯音乐顶替**
- 拒绝授权 / 永久拒绝后引导到系统设置 / 通话占麦时降级为「纯音乐」并显示原因
- 三态来回切换、混音态下暂停与切歌、切到纯麦克风后再切回混音
- 观感：浅色 / 深色、开启与关闭「卡片透明」、手势导航与三键导航下底栏贴底间距

## 六、已知边界（不藏着）

1. HOOK_SIDE 只覆盖 Java `AudioRecord`。native 混音要改 `glass_aaudio.cpp` / `glass_opensl.cpp` 的环形缓冲，在无编译器、无真机条件下盲改 C++ 风险过高，本轮刻意没做。
2. 切回真实麦克风后，目标 App **已打开的录音会话**不会即时生效，需重开录音或再切一次三态。
3. TTS 音源参与混音时，合成语音也会与人声叠加，需要真机试听调 `micGain`。
4. 悬浮窗 `FloatingBubbleUi` / `WaveformOverlayUi` 未纳入本轮 MiuiX 改版，范围限定在 Activity 内四个标签页。

---

内容由 AI 生成
