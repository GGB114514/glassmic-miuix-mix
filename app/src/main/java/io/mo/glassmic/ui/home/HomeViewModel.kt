package io.mo.glassmic.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.audio.MicCaptureMixer
import io.mo.glassmic.data.config.audioPolicyTargets
import io.mo.glassmic.core.model.MicMixMode
import io.mo.glassmic.core.model.MixEngine
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.PlaybackPolicy as ProtoPolicy
import io.mo.glassmic.proto.ScopeMode as ProtoScope
import io.mo.glassmic.service.FloatingWindowService
import io.mo.glassmic.service.GlassForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val running: Boolean = false,
    val hasFileSource: Boolean = false,
    val paused: Boolean = false,
    val sourceName: String = "",
    val groupName: String = "—",
    val policyLabel: String = "循环",
    val scopeLabel: String = "全局",
    val floatingWindowVisible: Boolean = false,
    val isStreaming: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val hookActivity: HookActivity = HookActivity.NEVER_PINGED,
    val hookLastPkg: String? = null,
    val hookLastPingMs: Long = 0L,
    val hookApi: Int = 0,
    val interceptReads: Long = 0L,
    val interceptBytes: Long = 0L,
    val interceptLastMs: Long = 0L,
    val interceptLastPkg: String? = null,
    val audioPolicyBackend: Boolean = false,
    val playbackPolicy: ProtoPolicy = ProtoPolicy.LOOP,
    val sourceEmoji: String = "🎵",
    val groupPlainName: String = "",
    /** 生效范围卡片上展示的前几个应用包名。 */
    val scopePackages: List<String> = emptyList(),
    val scopeAllApps: Boolean = false,
    /** 主页三态：纯音乐注入 / 音乐+麦克风混音 / 纯真实麦克风。 */
    val micMixMode: MicMixMode = MicMixMode.MUSIC_ONLY,
    /** 当前混音实现层，仅用于状态文案（HOOK_SIDE 下不占用本进程麦克风）。 */
    val mixEngine: MixEngine = MixEngine.APP_SIDE
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeStateHolder,
    private val configStore: ConfigStore,
    private val bootGate: BootGateRepository,
    private val safeModeRepo: SafeModeRepository,
    private val playback: PlaybackController,
    audioPolicy: io.mo.glassmic.root.AudioPolicyController,
    audioDao: AudioDao,
    hookStatusRepo: HookStatusRepository,
    audioStatsRepo: AudioStatsRepository
) : ViewModel() {
    val policyStatus = audioPolicy.status

    private val groupsFlow = audioDao.observeGroups()
    private val clipsFlow = audioDao.observeAllClips()
    private val hookFlow = hookStatusRepo.flow
    private val statsFlow = audioStatsRepo.flow

    val state: StateFlow<HomeUiState> = combine(
        combine(runtime.flow, configStore.flow) { rt, cfg -> rt to cfg },
        combine(groupsFlow, clipsFlow) { g, c -> g to c },
        combine(hookFlow, statsFlow) { h, s -> h to s }
    ) { rtCfg, groupsClips, hookStats ->
        val (rt, cfg) = rtCfg
        val (groups, clips) = groupsClips
        val (hook, stats) = hookStats
        val clip = clips.firstOrNull { it.id == rt.currentAudioId }
        val group = groups.firstOrNull { it.id == rt.currentGroupId }
        HomeUiState(
            audioPolicyBackend = cfg.injectionBackend == io.mo.glassmic.proto.InjectionBackend.AUDIO_POLICY,
            running = rt.enabled && !rt.safeMode && bootGate.userEnabledAfterBoot(),
            hasFileSource = (rt.currentSourceType == SourceType.FILE && clip != null) || (rt.currentSourceType == SourceType.TTS && rt.durationMs > 0),
            paused = rt.paused,
            sourceName = when (rt.currentSourceType) {
                SourceType.FILE -> clip?.displayName ?: rt.currentAudioId ?: "—"
                SourceType.REAL_MIC -> AppLocale.string(context, R.string.source_real_mic)
                SourceType.SILENCE -> AppLocale.string(context, R.string.source_silence)
                SourceType.TTS -> AppLocale.string(context, R.string.source_tts)
            },
            groupName = group?.let { "${it.emoji} ${it.name}" } ?: "—",
            sourceEmoji = when (rt.currentSourceType) {
                SourceType.FILE -> group?.emoji ?: "🎵"
                SourceType.TTS -> "🗣"
                SourceType.SILENCE -> "🔇"
                SourceType.REAL_MIC -> "🎙️"
            },
            groupPlainName = when (rt.currentSourceType) {
                SourceType.FILE -> group?.name.orEmpty()
                else -> ""
            },
            playbackPolicy = cfg.playbackPolicy,
            scopeAllApps = cfg.injectionBackend == io.mo.glassmic.proto.InjectionBackend.AUDIO_POLICY && cfg.audioPolicyAllApps,
            scopePackages = (if (cfg.injectionBackend == io.mo.glassmic.proto.InjectionBackend.AUDIO_POLICY)
                cfg.audioPolicyTargets() else cfg.whitelistList).take(4),
            policyLabel = when (cfg.playbackPolicy) {
                ProtoPolicy.SILENCE -> AppLocale.string(context, R.string.home_policy_silence)
                ProtoPolicy.LOOP -> AppLocale.string(context, R.string.library_policy_loop)
                ProtoPolicy.REAL_MIC -> AppLocale.string(context, R.string.library_policy_real_mic)
                else -> AppLocale.string(context, R.string.library_policy_loop)
            },
            scopeLabel = if (cfg.injectionBackend == io.mo.glassmic.proto.InjectionBackend.AUDIO_POLICY) {
                val targets = cfg.audioPolicyTargets()
                when {
                    cfg.audioPolicyAllApps -> AppLocale.string(context, R.string.scope_all_apps_selected)
                    targets.isEmpty() -> AppLocale.string(context, R.string.scope_none_selected)
                    targets.size == 1 -> targets.first()
                    else -> AppLocale.string(context, R.string.home_scope_whitelist_count, targets.size)
                }
            } else if (cfg.whitelistCount > 0) {
                AppLocale.string(context, R.string.home_scope_whitelist_count, cfg.whitelistCount)
            } else {
                AppLocale.string(context, R.string.scope_none_selected)
            },
            floatingWindowVisible = rt.floatingWindowVisible,
            isStreaming = rt.isStreaming,
            positionMs = rt.positionMs,
            durationMs = rt.durationMs,
            progress = if (rt.durationMs > 0) rt.positionMs.toFloat() / rt.durationMs else 0f,
            hookActivity = hook.activity,
            hookLastPkg = hook.lastPackage,
            hookLastPingMs = hook.lastPingMs,
            hookApi = hook.api,
            interceptReads = stats.totalReads,
            interceptBytes = stats.totalBytes,
            interceptLastMs = stats.lastInterceptMs,
            interceptLastPkg = stats.lastPackage,
            // 三态：混音开关优先——处于「纯麦克风」时开关必然已经关闭
            micMixMode = when {
                cfg.micMix.enabled -> MicMixMode.MIX
                rt.currentSourceType == SourceType.REAL_MIC -> MicMixMode.MIC_ONLY
                else -> MicMixMode.MUSIC_ONLY
            },
            mixEngine = when (cfg.micMix.engine) {
                io.mo.glassmic.proto.MixEngine.HOOK_SIDE -> MixEngine.HOOK_SIDE
                else -> MixEngine.APP_SIDE
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun togglePause() {
        playback.togglePause()
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { playback.seekTo(positionMs) }
    }

    fun toggleMaster(enable: Boolean) {
        viewModelScope.launch {
            if (enable) {
                if (safeModeRepo.isActive()) return@launch
                bootGate.markEnabledForThisBoot()
                configStore.update { it.setGlobalSwitch(true) }
                GlassForegroundService.start(context)
            } else {
                configStore.update { it.setGlobalSwitch(false) }
                bootGate.clear()
                GlassForegroundService.stop(context)
                FloatingWindowService.stop(context)
            }
        }
    }

    fun toggleFloating() {
        if (runtime.value.floatingWindowVisible) {
            FloatingWindowService.stop(context)
        } else {
            FloatingWindowService.start(context)
        }
    }

    fun setPolicy(policy: ProtoPolicy) {
        viewModelScope.launch { configStore.update { it.setPlaybackPolicy(policy) } }
    }

    fun restoreRealMic() {
        viewModelScope.launch { playback.setRealMic() }
    }

    // ============ 三态与麦克风混音 ============

    /** 三态切换被挡下的原因——UI 据此决定是弹授权框还是引导选曲。 */
    enum class MixReject { NONE, NO_PERMISSION, NO_CLIP, NEED_PERMISSION }

    private val _mixReject = MutableStateFlow(MixReject.NONE)
    val mixReject: StateFlow<MixReject> = _mixReject.asStateFlow()
    fun consumeMixReject() { _mixReject.value = MixReject.NONE }

    /** 音乐注入开关的当前状态（未设置按开启处理，与 proto 的 optional 语义一致）。 */
    val musicInject: StateFlow<Boolean> = configStore.flow
        .map { if (it.hasMusicInject()) it.musicInject else true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun toggleMusicInject() {
        viewModelScope.launch { playback.toggleMusicInject() }
    }

    /** 应用侧采集是否在跑。HOOK_SIDE 引擎下恒为 IDLE，那是预期状态而不是故障。 */
    val micCapState: StateFlow<MicCaptureMixer.CapState> get() = playback.micCapState

    /** 混音降级原因 key（mic_permission / mic_busy / mic_unsupported / mic_interrupted）。 */
    val micNotice: StateFlow<String?> get() = playback.micMixNotice

    private var mixPendingAfterGrant = false

    /**
     * 切换三态。
     *
     * 混音态需要 RECORD_AUDIO：没有授权时**不静默降级**，而是回调 [onNeedPermission]
     * 让 UI 发起系统授权，并在授权成功后自动补上这次切换。
     */
    fun setMixMode(mode: MicMixMode, onNeedPermission: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = playback.setMicMixMode(mode)
            if (!ok) {
                _mixReject.value = MixReject.NO_CLIP
                return@launch
            }
            // 混音不再被权限卡住：没授权时会自动走 HOOK_SIDE（借用目标应用已授权的录音会话）。
            // 这里只是顺手邀请授权，好让应用侧采集那条路也能用——拒绝也完全不影响混音。
            if (mode == MicMixMode.MIX && !micGrantedNow()) {
                mixPendingAfterGrant = true
                _mixReject.value = MixReject.NEED_PERMISSION
                onNeedPermission()
            } else {
                _mixReject.value = MixReject.NONE
            }
        }
    }

    /** 系统授权结果回来时调用。 */
    fun onMicPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            playback.refreshMicCapture()
            if (granted && mixPendingAfterGrant) {
                mixPendingAfterGrant = false
                _mixReject.value = if (playback.setMicMixMode(MicMixMode.MIX)) {
                    MixReject.NONE
                } else {
                    MixReject.NO_CLIP
                }
            } else if (!granted) {
                mixPendingAfterGrant = false
                _mixReject.value = MixReject.NO_PERMISSION
            }
        }
    }

    private fun micGrantedNow(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 权限被永久拒绝后只能去系统设置页手动开启。 */
    fun openMicSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${io.mo.glassmic.core.Constants.APP_PACKAGE}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { GlassLog.b("Home") { "打开应用设置失败: ${it.message}" } }
    }
}
