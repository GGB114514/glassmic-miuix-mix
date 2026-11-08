package io.mo.glassmic.core.model

/**
 * 运行态——不持久化，仅在内存与跨进程查询。
 * 任何字段的变化都要经过 EffectiveSourceResolver。
 */
data class RuntimeState(
    val enabled: Boolean = false,
    val safeMode: Boolean = false,
    val currentSourceType: SourceType = SourceType.REAL_MIC,
    val currentGroupId: String? = null,
    val currentAudioId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = false,
    val playbackPolicy: PlaybackPolicy = PlaybackPolicy.LOOP,
    val floatingWindowVisible: Boolean = false,
    val isStreaming: Boolean = false,
    val lastError: String? = null,
    /** 混音是否真的在采集麦克风（APP_SIDE 且采集线程活着）。 */
    val micMixing: Boolean = false,
    /** 混音降级原因：权限被拒 / 麦克风被占用 / 设备不支持。null 表示没有降级。 */
    val micMixNotice: String? = null
)

/**
 * 配置快照——给 Xposed 进程做 scope 匹配用，必须是 Serializable 且字段稳定。
 */
data class ConfigSnapshot(
    val globalSwitch: Boolean,
    val scopeMode: ScopeMode,
    val whitelist: Set<String>,
    val blacklist: Set<String>,
    val onboardingCompleted: Boolean,
    val audioPolicyBackend: Boolean = false,
    val audioPolicyPackages: Set<String> = emptySet(),
    val audioPolicyAllApps: Boolean = false,
    // ===== 麦克风混音（全部带默认值，旧调用点不受影响）=====
    val mixEnabled: Boolean = false,
    val mixEngine: MixEngine = MixEngine.APP_SIDE,
    val musicGain: Float = 1f,
    val micGain: Float = 1f,
    val mixDucking: Boolean = false,
    /** 音乐注入总开关。false 时目标应用直接走真实麦克风，与播放/暂停无关。 */
    val musicInjectEnabled: Boolean = true,
    val mixButtonAction: MixButtonAction = MixButtonAction.TOGGLE_MIX
)
