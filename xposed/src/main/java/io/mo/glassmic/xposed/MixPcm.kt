package io.mo.glassmic.xposed

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HOOK_SIDE 混音的核心算术：把注入 PCM 叠加到"真实麦克风已经读到的数据"上。
 *
 * 与 APP_SIDE 的区别在于取真实声音的方式——这里借用目标 App 自己已经授权的录音会话，
 * 先让原始 AudioRecord.read 把真人声音写进缓冲区，再把音乐加上去。
 * 因此不新增并发录音、不额外占用麦克风、延迟也更低。
 *
 * 语义约定（全程 PCM16 小端 / PCM32 float）：
 * - `mic` = 缓冲区里已有的真实麦克风数据，乘 [XBridge.MixDecision.micGain]
 * - `music` = 从注入管道读到的数据，乘 [XBridge.MixDecision.musicGain]
 * - 两者相加后截断到有符号范围，绝不回绕（回绕会变成刺耳爆音）
 */
object MixPcm {

    /**
     * ducking 包络：说话时把音乐压低，安静后缓慢抬回。
     *
     * 单个 volatile 字段跨进程内的多个 AudioRecord 共享——精度要求很低
     * （只是听感上的避让），不值得为它做每实例状态管理。
     */
    @Volatile
    private var envelope = 1f

    private const val DUCK_THRESHOLD = 900.0
    private const val DUCK_LEVEL = 0.35f
    private const val ATTACK = 0.5f
    private const val RELEASE = 0.05f

    /** 每帧重新计算，避免长时间静音后包络卡在低位。 */
    private fun duckStep(target: Float) {
        val rate = if (target < envelope) ATTACK else RELEASE
        envelope += (target - envelope) * rate
    }

    /** PCM16 小端：[target] 里已是真实麦克风数据，叠加 [music]（注入音频）。 */
    fun addPcm16(
        target: ByteArray,
        offset: Int,
        length: Int,
        music: ByteArray,
        musicLength: Int,
        mix: XBridge.MixDecision
    ) {
        if (mix.ducking) {
            var squares = 0.0
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                val s = sample16(target, i)
                squares += s.toDouble() * s
                i += 2
            }
            duckStep(if (sqrt(squares / (length / 2).coerceAtLeast(1)) > DUCK_THRESHOLD) DUCK_LEVEL else 1f)
        }
        val mg = mix.musicGain * (if (mix.ducking) envelope else 1f)
        val cg = mix.micGain
        val pairs = minOf(length / 2, musicLength / 2)
        var i = 0
        while (i < pairs) {
            val voice = sample16(target, offset + i * 2)
            val music16 = sample16(music, i * 2)
            val v = clamp(voice * cg + music16 * mg)
            write16(target, offset + i * 2, v)
            i++
        }
        // 注入流比真实数据短（管道不足一帧）时，剩余部分保留纯人声，不写 0 造成断续
    }

    /** ShortArray 版：语义同 [addPcm16]。 */
    fun addPcm16Shorts(
        target: ShortArray,
        offset: Int,
        count: Int,
        music: ByteArray,
        musicLength: Int,
        mix: XBridge.MixDecision
    ) {
        if (mix.ducking) {
            var squares = 0.0
            for (i in 0 until count) {
                val s = target[offset + i].toDouble()
                squares += s * s
            }
            duckStep(if (sqrt(squares / count.coerceAtLeast(1)) > DUCK_THRESHOLD) DUCK_LEVEL else 1f)
        }
        val mg = mix.musicGain * (if (mix.ducking) envelope else 1f)
        val cg = mix.micGain
        val pairs = minOf(count, musicLength / 2)
        var i = 0
        while (i < pairs) {
            val voice = target[offset + i].toDouble()
            val music16 = ((music[i * 2 + 1].toInt() and 0xFF shl 8) or (music[i * 2].toInt() and 0xFF)).toShort()
            target[offset + i] = clamp(voice * cg + music16 * mg)
            i++
        }
    }

    /**
     * PCM_FLOAT 版：目标缓冲区里是 -1..1 的浮点采样，
     * 注入管道给出的是 PCM16，需要除以 32768 归一后相加。
     */
    fun addFloatBytes(
        target: ByteArray,
        offset: Int,
        length: Int,
        music: ByteArray,
        musicLength: Int,
        mix: XBridge.MixDecision
    ) {
        val mg = mix.musicGain * if (mix.ducking) envelope else 1f
        val cg = mix.micGain
        val floats = minOf(length / 4, musicLength / 2)
        var i = 0
        while (i < floats) {
            val bi = offset + i * 4
            val bits = (target[bi + 3].toInt() and 0xFF shl 24) or
                (target[bi + 2].toInt() and 0xFF shl 16) or
                (target[bi + 1].toInt() and 0xFF shl 8) or
                (target[bi].toInt() and 0xFF)
            val voice = bits.toFloat()
            val music16 = ((music[i * 2 + 1].toInt() and 0xFF shl 8) or (music[i * 2].toInt() and 0xFF)).toShort()
            val sum = clampFloat(voice * cg + (music16 / 32768f) * mg)
            val out = java.lang.Float.floatToIntBits(sum)
            target[bi] = (out and 0xFF).toByte()
            target[bi + 1] = ((out ushr 8) and 0xFF).toByte()
            target[bi + 2] = ((out ushr 16) and 0xFF).toByte()
            target[bi + 3] = ((out ushr 24) and 0xFF).toByte()
            i++
        }
    }

    /** PCM16 小端 → [target]（真实麦克风 ShortArray），叠加注入音乐。 */
    fun addFloats(
        target: FloatArray,
        offset: Int,
        count: Int,
        music: ByteArray,
        musicLength: Int,
        mix: XBridge.MixDecision
    ) {
        if (mix.ducking) {
            var squares = 0.0
            for (i in 0 until count) {
                val s = target[offset + i] * 32768f
                squares += s.toDouble() * s
            }
            duckStep(if (sqrt(squares / count.coerceAtLeast(1)) > DUCK_THRESHOLD) DUCK_LEVEL else 1f)
        }
        val mg = mix.musicGain * (if (mix.ducking) envelope else 1f)
        val cg = mix.micGain
        val pairs = minOf(count, musicLength / 2)
        var i = 0
        while (i < pairs) {
            val voice = target[offset + i]
            val music16 = ((music[i * 2 + 1].toInt() and 0xFF shl 8) or (music[i * 2].toInt() and 0xFF)).toShort()
            target[offset + i] = clampFloat(voice * cg + (music16 / 32768f) * mg)
            i++
        }
    }

    /**
     * ByteBuffer 版（read(ByteBuffer, sizeInBytes) 路径）。
     *
     * 用绝对下标读写，不改动调用方的 position——目标 App 依赖 position 语义。
     */
    fun addPcm16Buffer(
        target: ByteBuffer,
        start: Int,
        length: Int,
        music: ByteArray,
        musicLength: Int,
        mix: XBridge.MixDecision,
        floatFormat: Boolean
    ) {
        if (mix.ducking && !floatFormat) {
            var squares = 0.0
            var at = start
            val end = start + length
            while (at + 1 < end) {
                val s = ((target.get(at + 1).toInt() and 0xFF shl 8) or (target.get(at).toInt() and 0xFF)).toShort()
                squares += s.toDouble() * s
                at += 2
            }
            duckStep(if (sqrt(squares / (length / 2).coerceAtLeast(1)) > DUCK_THRESHOLD) DUCK_LEVEL else 1f)
        }
        val mg = mix.musicGain * (if (mix.ducking) envelope else 1f)
        val cg = mix.micGain
        if (floatFormat) {
            val floats = minOf(length / 4, musicLength / 2)
            var i = 0
            while (i < floats) {
                val at = start + i * 4
                val bits = (target.get(at + 3).toInt() and 0xFF shl 24) or
                    (target.get(at + 2).toInt() and 0xFF shl 16) or
                    (target.get(at + 1).toInt() and 0xFF shl 8) or
                    (target.get(at).toInt() and 0xFF)
                val music16 = ((music[i * 2 + 1].toInt() and 0xFF shl 8) or (music[i * 2].toInt() and 0xFF)).toShort()
                val out = java.lang.Float.floatToIntBits(clampFloat(bits.toFloat() * cg + (music16 / 32768f) * mg))
                target.put(at, (out and 0xFF).toByte())
                target.put(at + 1, ((out ushr 8) and 0xFF).toByte())
                target.put(at + 2, ((out ushr 16) and 0xFF).toByte())
                target.put(at + 3, ((out ushr 24) and 0xFF).toByte())
                i++
            }
            return
        }
        val pairs = minOf(length / 2, musicLength / 2)
        var i = 0
        while (i < pairs) {
            val at = start + i * 2
            val voice = ((target.get(at + 1).toInt() and 0xFF shl 8) or (target.get(at).toInt() and 0xFF)).toShort()
            val music16 = ((music[i * 2 + 1].toInt() and 0xFF shl 8) or (music[i * 2].toInt() and 0xFF)).toShort()
            val v = clamp(voice * cg + music16 * mg).toInt()
            target.put(at, (v and 0xFF).toByte())
            target.put(at + 1, ((v ushr 8) and 0xFF).toByte())
            i++
        }
    }

    private fun sample16(b: ByteArray, at: Int): Int =
        ((b[at + 1].toInt() and 0xFF shl 8) or (b[at].toInt() and 0xFF)).toShort().toInt()

    private fun write16(b: ByteArray, at: Int, v: Short) {
        b[at] = (v.toInt() and 0xFF).toByte()
        b[at + 1] = ((v.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun clamp(v: Double): Short = when {
        v > Short.MAX_VALUE -> Short.MAX_VALUE
        v < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> v.toInt().toShort()
    }

    /**
     * Float 版截断。
     *
     * 混音算式里 `人声(Int) * 电平(Float)` 结果是 Float，不加这个重载就只能到处 .toDouble()，
     * 而 Float 直接到 Double 是隐式 widening——Kotlin 不做隐式转换，会直接编译失败。
     */
    private fun clamp(v: Float): Short = when {
        v > Short.MAX_VALUE -> Short.MAX_VALUE
        v < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> v.toInt().toShort()
    }

    private fun clampFloat(v: Float): Float = when {
        v > 1f -> 1f
        v < -1f -> -1f
        else -> v
    }

    /** 供诊断使用：绝对值，避免到处引 kotlin.math。 */
    fun magnitude(v: Float): Float = abs(v)
}
