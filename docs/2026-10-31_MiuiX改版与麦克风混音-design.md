# GlassMic × MiuiX 改版 + 麦克风混音 · 设计与验证记录

日期：2026-10-31　　基线：`lm060719/io.mo.glassmic` @ v1.4.8 (versionCode 27)

## 1. 需求与最终决策

| 需求 | 决策 |
|---|---|
| UI 改成 MiuiX 风格 | **自建 HyperOS 风格层，不引入 MiuiX 依赖**（避免被迫把 Compose 1.7.6→1.9.3、Kotlin 2.2.10→2.2.21 全栈升级；本环境无法编译验证，升级风险不可接受） |
| 底部栏改为 主页 / 应用列表 / 音乐 / 设置 | 原三栏（麦克风 / 音频库 / 设置）扩为四栏；`ScopeScreen`（应用列表）从设置里的二级页**提升为一等标签** |
| 申请麦克风权限 | `PermissionChecker` 新增 `mic` 状态；新增运行时授权组件；引导页补一行「麦克风（可选）」 |
| 边放音乐边能用麦克风 | 三态互斥：**纯音乐 / 混音 / 纯麦克风**，在主页直接可选 |
| 混音两条路线 | 全部实现，**设置页可切换**：`APP_SIDE`（本应用采集后混入 PCM 管道）与 `HOOK_SIDE`（在目标进程借用其已授权录音会话叠加） |

## 2. 混音架构

```
                     ┌── engine = APP_SIDE ──────────────────────────┐
                     │  MicCaptureMixer (AudioRecord 48k/mono/PCM16) │
                     │        ↓ ShortRing                            │
SharedPcmPublisher ──┤  broadcast(): applyEffects() → mixInto()      │→ PcmStreamProvider → 目标进程
   (音乐/TTS 源)      │        （效果链之后叠加，人声不被混响污染）    │
                     └── engine = HOOK_SIDE ─────────────────────────┘
                        RuntimeProvider /resolve 附带 mix 列
                              ↓ XBridge.MixDecision
                        AudioRecordHook.read*: 原语 proceed() 取真实麦克风
                              → MixPcm.add*(真实数据, 注入流, 电平)
```

两条路线互斥：`HOOK_SIDE` 时 `MicCaptureMixer.desiredActive()` 返回 false，绝不重复叠加。

**三态映射**（不新增 `SourceType`，避免破坏各处 `when` 穷尽性）：

- `MUSIC_ONLY` = 混音关 + 注入流（旧行为）
- `MIX` = 混音开 + 注入流
- `MIC_ONLY` = `setRealMic()`，完全放行真实麦克风

**降级策略**（与模块 "fail toward real microphone" 安全模型一致）：权限缺失、设备占用、参数不支持、连续读取失败 → 采集状态显式记为 `NO_PERMISSION / DEVICE_BUSY / UNSUPPORTED / ERROR`，主页与设置页直接显示，不伪装成"正在混音"。

## 3. 变更清单（26 个文件）

**新增**

- `app/.../audio/MicCaptureMixer.kt` — 应用侧采集 + 环形缓冲 + 电平混合 + ducking + 状态机
- `xposed/.../MixPcm.kt` — 目标进程内混音算术（PCM16 / ShortArray / FloatArray / ByteBuffer 四种目标格式）
- `app/.../ui/common/Miuix.kt` — MiuiX 通栏底栏、分组标题、页面大标题、状态徽标
- `app/.../ui/common/MicPermission.kt` — RECORD_AUDIO 运行时授权封装

**修改**

- 模型/配置：`core/model/Enums.kt`（`MixEngine`、`MicMixMode`）、`core/model/RuntimeState.kt`、`app_config.proto`（`MicMix` + `MixEngine`，字段号 25，默认关闭对老用户零影响）、`ConfigStore.kt`（快照 + 共享偏好同步）、`RuntimeStateHolder.kt`（`setMicMixing`）
- 音频链路：`SharedPcmPublisher.kt`（效果链后调用 `mixInto`）、`PlaybackController.kt`（三态切换与混音参数写入）、`RuntimeProvider.kt`（下发 mix 列）、`XBridge.kt`（解析并缓存 `MixDecision`）、`AudioRecordHook.kt`（四条 read 路径的 HOOK_SIDE 混音）
- 权限：`PermissionChecker.kt`（`mic` + `checkMic` + 永久拒绝判定 + 设置页跳转）、`OnboardingFlow.kt`（麦克风一行 + 直接弹系统框）
- UI：`MainActivity.kt`（四栏 + MiuiX 底栏）、`GlassTokens.kt`（HyperOS 配色/圆角/inset token）、`GlassKit.kt`（卡片、开关 51×31、分段控件、设置行 60dp、大标题 34sp、分组标题）、`HomeScreen/HomeViewModel`（三态卡片与授权交互）、`SettingsScreen/SettingsViewModel`（「麦克风混音」分区：开关 / 引擎切换 / 两路电平 / ducking / 权限行 / 采集状态）、`ScopeScreen.kt`（`asTab` 支持）

## 4. 已完成的验证（真实执行，非推断）

1. **core 模块完整编译通过** —— 独立 Kotlin 2.2.10 编译器 + stdlib，产出 18 个 class，含新增 `MixEngine.class`、`MicMixMode.class`。
2. **xposed 模块完整编译通过** —— classpath 为 android-36 `android.jar` + libxposed API 101 + legacy API 82 + shadowhook 1.0.10 + core 产物：**0 错误，46 个 class**，含 `MixPcm.class`、`XBridge$MixDecision.class`、`AudioRecordHook.class`。
   - 过程中发现并修复了 2 个真实类型错误：`clamp()` 只有 Double 重载，而混音算式里 `Int * Float` 结果是 Float。
3. **proto schema 通过 protoc 3.25.5 校验**，并确认生成 API 与调用点完全一致：`AppConfig.getMicMix/setMicMix`，`MicMix.Builder.setEnabled/setEngine/setMusicGain/setMicGain/setDucking`，`MixEngine{APP_SIDE,HOOK_SIDE}`。
4. 其余文件（app 模块，依赖 Compose/Hilt/Room/DataStore）做了括号与引号配平自检，全部平衡。

## 5. 未完成的验证 —— 必须在你本地补做

这台云电脑**出不了 APK**：Gradle 9.5 在容器里连自己的缓存锁服务都起不来（`Could not create service of type FileHasher → IOException: Invalid argument`；换 JDK 17/21、`--no-daemon`、把 `GRADLE_USER_HOME` 挪到 ext4 卷都失败，属容器内核层限制）；`maven.google.com` 亦不可达（可用 `maven.aliyun.com/repository/google` 镜像绕过）。

所以 **app 模块的 Kotlin 源码没有经过编译器检验**，只有人工审查。请按下述顺序复查：

1. `./gradlew assembleDebug` —— 首要报错预计集中在：
   - `HomeScreen.kt` 的 `MicModeCard` 参数名与 `Segmented`/`PrimaryButton`/`SoftButton` 实参
   - `SettingsScreen.kt` 新增分区里 `vm::setMix*` 与 `StackedRow` 的重载匹配
   - `MainActivity.kt` 里因删除浮动底栏而残留的未使用 import
2. 运行期必测（混音是行为级功能，静态检查测不出来）：
   - `APP_SIDE`：微信语音输入页，播放音频 + 对着麦克风说话，确认两者都被录入
   - `HOOK_SIDE`：同上；另需确认目标 App 用 `read(byte[],int,int)` / `read(short[]...)` / `read(float[]...)` / `read(ByteBuffer,int)` 中哪条路径，native AAudio/OpenSL 路径**当前不混音**（仍是纯音乐顶替），这是已知边界
   - 拒绝授权 / 通话占麦 / 三态来回切换 / 混音态下暂停与切歌
   - 并发录音被 ROM 限制时（HyperOS 常见），确认走的是"降级并提示"而不是"静默无声"
3. 观感验收：浅色/深色、开启与关闭「卡片透明」、底栏四栏在手势导航与三键导航下的贴底间距

## 6. 已知边界与取舍（不藏着）

- `HOOK_SIDE` 只覆盖 Java `AudioRecord`。AAudio/OpenSL 的 native 混音需要改 `glass_aaudio.cpp` / `glass_opensl.cpp` 的环形缓冲，在无编译器、无真机的条件下盲改 C++ 风险过高，本轮**刻意没做**。
- `APP_SIDE` 的引擎选择存在一个理论盲区：`runtime.setSource(REAL_MIC)` 后本进程不再对外发 PCM，所以本进程侧的混音对**已经打开的录音会话**不会即时生效，需要目标 App 重新打开录音（或经三态切一次）。
- 与 TTS 音源叠加时，TTS 语音也会被一同混入，效果与人声叠加需要真机试听调 `micGain`。
- 未改动 `FloatingBubbleUi` / `WaveformOverlayUi`（悬浮窗）与 `AudioRecord` 之外的其它 hook 的 MiuiX 外观；本轮范围限定在 Activity 内的四个标签页。

---

内容由 AI 生成
