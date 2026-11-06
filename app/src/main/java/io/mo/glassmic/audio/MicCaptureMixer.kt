package io.mo.glassmic.audio

import android.annotation.SuppressLint
import android.content.Context
import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.MixEngine
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 应用侧混音引擎（[MixEngine.APP_SIDE]）。
 *
 * GlassMic 自己开一路 [AudioRecord] 采集真实麦克风，样本写入环形缓冲；
 * [SharedPcmPublisher] 在每帧广播前调用 [mixInto]，把麦克风数据叠加到注入流上。
 * 目标 App 因此同时收到「导入的音乐」和「真人说话」，而不被音乐顶替。
 *
 * 采集固定 48kHz / 单声道 / PCM16——与发布管道的主时钟
 * （SharedPcmPublisher.MASTER_SAMPLE_RATE / MASTER_CHANNELS）一致，
 * 因此管道内不需要重采样，只剩电平混合。
 *
 * 失败一律降级为「不混音」，绝不因为采不到麦克风就把音乐也断掉：
 * 这与模块整体「fail toward real microphone」的安全模型一致。
 */
@Singleton
class MicCaptureMixer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder
) {

    /** 采集线程状态——UI 直接据此显示「混音中 / 已降级」。 */
    enum class CapState {
        /** 没在混音（关闭、纯音乐、或走 HOOK_SIDE 引擎）。 */
        IDLE,

        /** 正常采集中。 */
        RUNNING,

        /** 未授予 RECORD_AUDIO——混音不可用。 */
        NO_PERMISSION,

        /** 麦克风被更高优先级的使用者（通话等）独占。 */
        DEVICE_BUSY,

        /** 设备不支持该采集参数，或 AudioRecord 初始化失败。 */
        UNSUPPORTED,

        /** 采集线程意外异常。 */
        ERROR
    }

    private val _capState = MutableStateFlow(CapState.IDLE)
    val capState: StateFlow<CapState> = _capState.asStateFlow()

    /** 最近一次降级原因的英文 key，UI 负责本地化。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private data class MixParams(
        val enabled: Boolean = false,
        val engine: MixEngine = MixEngine.APP_SIDE,
        val musicGain: Float = 1f,
        val micGain: Float = 1f,
        val ducking: Boolean = false
    )

    @Volatile private var params = MixParams()
    @Volatile private var mixing = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startMutex = Mutex()

    /** 混音环形缓冲：约 1 秒容量，生产者是采集线程，消费者是发布协程。 */
    private val ring = ShortRing(SAMPLE_RATE)

    @Volatile private var captureThread: Thread? = null
    @Volatile private var stopRequested = false

    private var record: AudioRecord? = null

    // 压低音乐的包络：说话时降到 DUCK_LEVEL，停说话后缓慢回升
    private var duckEnvelope = 1f
    private var lastMicRms = 0.0

    init {
        // 配置与运行态任一变化都要重新决定「该不该采集」
        scope.launch {
            configStore.flow.collect { cfg ->
                params = MixParams(
                    enabled = cfg.micMix.enabled,
                    engine = when (cfg.micMix.engine) {
                        io.mo.glassmic.proto.MixEngine.HOOK_SIDE -> MixEngine.HOOK_SIDE
                        else -> MixEngine.APP_SIDE
                    },
                    musicGain = cfg.micMix.musicGain.takeIf { it > 0f } ?: 1f,
                    micGain = cfg.micMix.micGain.takeIf { it > 0f } ?: 1f,
                    ducking = cfg.micMix.ducking
                )
                sync()
            }
        }
        scope.launch {
            runtime.flow.collect { sync() }
        }
    }

    /** 权限刚变化时由 UI 调用：重新决定启停采集线程。 */
    fun refresh() = sync()

    /**
     * 该不该采集麦克风：混音开 + 走应用侧引擎 + 虚拟麦克风在跑且对外是真音源注入。
     *
     * HOOK_SIDE 引擎不占用本进程麦克风，所以这里必须停掉采集，
     * 否则同一次混音会被叠加两遍。
     */
    private fun desiredActive(): Boolean {
        val p = params
        val rt = runtime.value
        return p.enabled &&
            p.engine == MixEngine.APP_SIDE &&
            rt.enabled &&
            !rt.safeMode &&
            rt.currentSourceType != SourceType.REAL_MIC
    }

    private fun sync() {
        val want = desiredActive()
        mixing = want
        runtime.setMicMixing(active = want && _capState.value == CapState.RUNNING, notice = _notice.value)
        if (want) {
            scope.launch { ensureCapturing() }
        } else {
            stopCapturing(CapState.IDLE, null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureCapturing() = startMutex.withLock {
        if (!desiredActive()) return@withLock
        if (captureThread != null && _capState.value == CapState.RUNNING) return@withLock

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopCapturing(CapState.NO_PERMISSION, "mic_permission")
            return@withLock
        }

        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(0)
        if (minBuf <= 0) {
            stopCapturing(CapState.UNSUPPORTED, "mic_unsupported")
            return@withLock
        }

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // 4 倍最小缓冲：容忍一次调度抖动而不产生破音
                minBuf * 4
            )
        }.getOrElse {
            stopCapturing(CapState.ERROR, "mic_init_failed")
            GlassLog.b("MicMix") { "AudioRecord 创建失败: ${it.message}" }
            return@withLock
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            stopCapturing(CapState.UNSUPPORTED, "mic_unsupported")
            return@withLock
        }

        val started = runCatching { rec.startRecording() }.also {
            if (it.isFailure) GlassLog.b("MicMix") { "startRecording 失败: ${it.exceptionOrNull()?.message}" }
        }.isSuccess
        if (!started || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { rec.release() }
            // 启动失败最常见的就是麦克风被通话/其它 App 独占
            stopCapturing(CapState.DEVICE_BUSY, "mic_busy")
            return@withLock
        }

        record = rec
        ring.clear()
        stopRequested = false
        val chunk = ShortArray(minBuf.coerceIn(480, 4800))
        val t = Thread({
            var readErrors = 0
            var latencyGuard = 0
            while (!stopRequested) {
                val n = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (n <= 0) {
                    // AudioRecord.ERROR_BAD_VALUE(-2)/INVALID_OPERATION(-3) 多来自被更高优先级打断；
                    // 连续多次取不到数据才认定失败，单次抖动直接跳过本轮。
                    if (n < 0) {
                        readErrors++
                        if (readErrors >= MAX_READ_ERRORS) {
                            GlassLog.b("MicMix") { "连续 $readErrors 次读取失败($n)，混音降级" }
                            stopCapturing(CapState.DEVICE_BUSY, "mic_interrupted")
                            break
                        }
                    }
                    continue
                }
                readErrors = 0
                ring.write(chunk, 0, n)
                // 采集快于消费说明目标侧没在取流，丢掉最新一段把延迟压回 250ms 以内，
                // 否则一恢复播放就会把积压的旧声音一起糊上去。
                if (++latencyGuard >= LATENCY_CHECK_CHUNKS) {
                    latencyGuard = 0
                    val backlog = ring.size()
                    if (backlog > MAX_BACKLOG_SAMPLES) ring.dropNewest(backlog - MAX_BACKLOG_SAMPLES)
                }
            }
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
            runCatching { rec.release() }
            if (record === rec) {
                record = null
                captureThread = null
            }
        }, "GlassMic-MicCapture").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2 }
        captureThread = t
        _capState.value = CapState.RUNNING
        _notice.value = null
        runtime.setMicMixing(active = true, notice = null)
        GlassLog.b("MicMix") { "应用侧采集已启动 @48kHz mono" }
        t.start()
    }

    private fun stopCapturing(state: CapState, notice: String?) {
        _capState.value = state
        _notice.value = notice
        runtime.setMicMixing(active = state == CapState.RUNNING, notice = notice)
        val t = captureThread
        stopRequested = true
        captureThread = null
        val rec = record
        record = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(300) }
        }
        runCatching {
            if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
        }
        runCatching { rec?.release() }
        ring.clear()
        duckEnvelope = 1f
        lastMicRms = 0.0
        if (state != CapState.IDLE) {
            GlassLog.b("MicMix") { "混音降级: $state ($notice)" }
        }
    }

    /**
     * 把采集到的麦克风数据叠加进一帧注入 PCM。
     *
     * 入参是小端 PCM16 单声道 48kHz（与 [SharedPcmPublisher] 主时钟一致），
     * 就地修改并返回同一数组；不混音时原样返回。
     *
     * 顺序很重要：本步发生在 [SharedPcmPublisher] 的效果链**之后**，
     * 所以音乐的高增益 / 混响 / 带通等实验效果不会污染人声。
     */
    fun mixInto(pcm: ByteArray): ByteArray {
        val p = params
        if (!mixing || p.engine != MixEngine.APP_SIDE || _capState.value != CapState.RUNNING) return pcm

        val samples = pcm.size / 2
        if (samples <= 0) return pcm

        val mic = ring.read(samples)
        val micCount = mic.second
        val buf = mic.first

        // 先量音乐本帧的 RMS，再决定压低多少
        var musicSquares = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = toShort(pcm[i], pcm[i + 1]).toDouble()
            musicSquares += s * s
            i += 2
        }
        val musicRms = sqrt(musicSquares / samples)

        // ducking：有人说话时把音乐压到 DUCK_LEVEL，安静后按 RELEASE 速度抬回
        if (p.ducking) {
            var micSquares = 0.0
            for (k in 0 until micCount) micSquares += buf[k].toDouble() * buf[k].toDouble()
            lastMicRms = if (micCount > 0) sqrt(micSquares / micCount) else 0.0
            val target = if (lastMicRms > DUCK_THRESHOLD) DUCK_LEVEL else 1f
            duckEnvelope = if (target < duckEnvelope) {
                duckEnvelope + (target - duckEnvelope) * ATTACK
            } else {
                duckEnvelope + (target - duckEnvelope) * RELEASE
            }
        } else {
            duckEnvelope = 1f
        }

        val mg = p.musicGain * duckEnvelope
        val cg = p.micGain
        i = 0
        var out = 0
        while (out < samples) {
            val music = toShort(pcm[i], pcm[i + 1]).toFloat()
            // 环形缓冲不足时用 0 补人声：宁可人声短暂缺失，也不让音乐出错
            val voice = if (out < micCount) buf[out].toFloat() else 0f
            val sum = music * mg + voice * cg
            val clipped = if (sum > Short.MAX_VALUE) Short.MAX_VALUE.toFloat()
            else if (sum < Short.MIN_VALUE) Short.MIN_VALUE.toFloat() else sum
            val v = clipped.toInt()
            pcm[i] = (v and 0xFF).toByte()
            pcm[i + 1] = ((v ushr 8) and 0xFF).toByte()
            i += 2
            out++
        }
        return pcm
    }

    /** 对外只暴露一个整数电平，供波形/悬浮窗显示「麦克风是否在动」。 */
    fun micLevelPercent(): Int = ((lastMicRms / 32768.0) * 100).coerceIn(0.0, 100.0).toInt()

    /** 释放采集资源。前台服务停止与安全模式都会走到这里。 */
    fun shutdown() = stopCapturing(CapState.IDLE, null)

    private fun toShort(lo: Byte, hi: Byte): Short =
        ((hi.toInt() and 0xFF shl 8) or (lo.toInt() and 0xFF)).toShort()

    /** 绝对值，避免 kotlin.math.abs 在 Int 边界上的额外导入成本。 */
    private fun abs(v: Float): Float = if (v < 0f) -v else v

    private companion object {
        // 必须与 SharedPcmPublisher 的主时钟一致，否则要引入重采样
        const val SAMPLE_RATE = 48_000

        // 说话判定阈值（RMS，约 -30dBFS）
        const val DUCK_THRESHOLD = 900.0
        const val DUCK_LEVEL = 0.35f
        const val ATTACK = 0.5f
        const val RELEASE = 0.05f

        /** 连续读取失败多少次才判定混音不可用（单次抖动不算）。 */
        const val MAX_READ_ERRORS = 8

        /** 每隔多少个采集块检查一次积压。 */
        const val LATENCY_CHECK_CHUNKS = 8

        /** 允许的最大积压样本数（250ms @48kHz）。 */
        const val MAX_BACKLOG_SAMPLES = 12_000
    }
}

/**
 * 单生产者 / 单消费者的短整型环形缓冲。
 *
 * 采集线程 [write]，发布协程 [read]。锁在这里不是瓶颈：20ms 一帧，
 * 竞争概率极低，换来的是任何时刻都不会读到撕裂数据。
 */
private class ShortRing(capacity: Int) {
    private val buf = ShortArray(capacity)
    private val lock = Any()
    private var head = 0   // 下一个写入位置
    private var tail = 0   // 下一个读出位置
    private var len = 0

    fun write(src: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            var n = count
            // 溢出：覆盖最旧数据，保证实时性优先
            if (len + n > buf.size) {
                val drop = len + n - buf.size
                tail = (tail + drop) % buf.size
                len -= drop
            }
            var w = head
            var i = offset
            while (n > 0) {
                val room = buf.size - w
                val take = minOf(room, n)
                System.arraycopy(src, i, buf, w, take)
                w += take
                i += take
                n -= take
                if (w == buf.size) w = 0
            }
            head = w
            len += count
        }
    }

    /** 返回 (数据, 实际读到的样本数)；不足一帧时返回现有长度，由调用方补零。 */
    fun read(requested: Int): Pair<ShortArray, Int> {
        val out = ShortArray(requested)
        synchronized(lock) {
            val take = minOf(requested, len)
            var r = tail
            var remaining = take
            var written = 0
            while (remaining > 0) {
                val room = buf.size - r
                val chunk = minOf(room, remaining)
                System.arraycopy(buf, r, out, written, chunk)
                r += chunk
                if (r == buf.size) r = 0
                written += chunk
                remaining -= chunk
            }
            tail = r
            len -= take
            return out to take
        }
    }

    fun size(): Int = synchronized(lock) { len }

    fun dropNewest(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            val d = minOf(count, len)
            head = (head - d + buf.size) % buf.size
            len -= d
        }
    }

    fun clear() = synchronized(lock) {
        head = 0; tail = 0; len = 0
    }
}

/** 采集线程里用的轻量计数器（避免再引一个 atomic 依赖）。 */
private class AtomicInteger32 {
    @Volatile private var v = 0
    fun nextAndClear(): Int {
        val old = v
        v = old + 1
        return old
    }
}
