package io.mo.glassmic.core.model

enum class SourceType { REAL_MIC, FILE, SILENCE, TTS }

enum class ScopeMode { GLOBAL, WHITELIST, BLACKLIST }

enum class PlaybackPolicy { SILENCE, LOOP, REAL_MIC }

/**
 * 混音实现层。
 *
 * - [APP_SIDE]：GlassMic 自己开 AudioRecord 采集真实麦克风，在发布 PCM 前与音乐混音。
 *   一处改动即覆盖 AudioRecord / AAudio / OpenSL 三条注入路径；需要 RECORD_AUDIO 授权。
 * - [HOOK_SIDE]：借用目标 App 已经授权的录音会话，在目标进程内先取原始数据再叠加注入流。
 *   不额外占用麦克风、延迟更低，目前只覆盖 Java AudioRecord 路径。
 */
enum class MixEngine { APP_SIDE, HOOK_SIDE }

/** 主页三态：纯音乐注入 / 音乐+麦克风混音 / 纯真实麦克风。 */
enum class MicMixMode { MUSIC_ONLY, MIX, MIC_ONLY }

/**
 * 悬浮窗麦克风按钮的行为。由用户在设置里选定，悬浮窗只负责执行——
 * 同一枚按钮在不同人手里想要的语义不一样，不该由我们单方面决定。
 */
enum class MixButtonAction { TOGGLE_MIX, TOGGLE_REAL_MIC }

enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

enum class FloatingMode { PILL, PANEL }

enum class FloatingSize { SMALL, STANDARD, LARGE }

enum class LogLevel { OFF, BASIC, VERBOSE, DEBUG }

enum class SafeModeReason {
    SYSTEM_UI_REPEATED_CRASH,
    KEY_SYSTEM_PROC_CRASH,
    MODULE_INIT_FAILURE,
    AUDIO_ENGINE_CONTINUOUS_FAILURE,
    LAST_BOOT_DID_NOT_EXIT_CLEANLY,
    USER_EMERGENCY_STOP
}

data class SafeModeInfo(
    val reason: SafeModeReason,
    val occurredAt: Long
)
