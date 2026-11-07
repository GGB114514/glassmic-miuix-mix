package io.mo.glassmic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 重设计（通透玻璃方案 A）的语义色 token。
 *
 * 与设计稿 `tok(dark, A, glass)` 一一对应：ink 三档文字、fill 填充、segOn 分段选中块、
 * ok / warn / err 三组状态色，以及卡片 / 底栏 / 底部面板的玻璃材质。
 * 关闭「卡片透明」时退化为不透明实色卡片，背景去掉彩色光斑。
 */
@Immutable
data class GlassTokens(
    val isDark: Boolean,
    val glass: Boolean,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val bgSolid: Color,
    val bgBase: Color,
    val fill: Color,
    val segOn: Color,
    val segShadow: Boolean,
    val primary: Color,
    val primarySoft: Color,
    val primaryInk: Color,
    val divider: Color,
    val dash: Color,
    val ok: Color,
    val okSoft: Color,
    val okInk: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnInk: Color,
    val err: Color,
    val errSoft: Color,
    val ringBorder: Color,
    /** 卡片材质：玻璃模式为 135° 斜向高光渐变。 */
    val card: Brush,
    /** 未选中但需要"卡片底"的小元素（分组 chip 等）用的近似纯色。 */
    val cardFlat: Color,
    val border: Color,
    /** 玻璃边缘受光：左上亮、右下淡，营造玻璃厚度。 */
    val rim: Brush,
    /** 卡片顶部 1px 内高光。 */
    val highlight: Color,
    val shadowColor: Color,
    val bar: Brush,
    val barBorder: Color,
    val sheet: Color,
    /** 背景三团径向光晕（左上蓝 / 右中橙 / 左下紫）的颜色。 */
    val glowBlue: Color,
    val glowOrange: Color,
    val glowPurple: Color,
    val orbAlpha: Float,
    /** 贴底通栏底栏的纯色底（MiuiX 底栏不浮动，见 MiuixBottomBar）。 */
    val barSolid: Color = if (isDark) Color(0xFF141416) else Color(0xFFF7F7F7),
    /** HyperOS 卡片圆角——比 iOS 风更收敛。 */
    val radiusCard: Dp = 16.dp,
    /** 徽标 / 小胶囊圆角。 */
    val radiusChip: Dp = 10.dp,
    /** 列表项左内缩：HyperOS 分组列表的分隔线与文字都从这里对齐。 */
    val listInset: Dp = 16.dp,
) {
    /** Backward-compatible aliases used by existing UI call sites. */
    val chipRadius: Dp get() = radiusChip
    val errInk: Color get() = err
}

val GlassPrimary = Color(0xFF3D7AFF)

/**
 * MiuiX（HyperOS）配色。
 *
 * - 强调色取 HyperOS 默认蓝 #0D84FF（深色下抬亮到 #66B5FF 保证对比度），
 *   想要 MIUI 经典橙把这一对换成 0xFFFF6900 / 0xFFFF9A4A 即可。
 * - 浅色底是列表灰 #F2F2F7，卡片是纯白；深色底 #0F0F11，卡片 #1E1E22——
 *   HyperOS 的分组列表靠"底色与卡片反差不大"体现层级，而不是靠阴影。
 */
fun glassTokens(dark: Boolean, glass: Boolean): GlassTokens {
    val ink = if (dark) Color(0xfff5f5f7) else Color(0xff1a1a1c)
    val accent = if (dark) Color(0xFF66B5FF) else Color(0xFF0D84FF)
    val base = GlassTokens(
        isDark = dark,
        glass = glass,
        ink = ink,
        ink2 = ink.copy(alpha = if (dark) 0.72f else 0.62f),
        ink3 = ink.copy(alpha = if (dark) 0.48f else 0.42f),
        bgSolid = if (dark) Color(0xFF1E1E22) else Color.White,
        bgBase = if (dark) Color(0xFF0F0F11) else Color(0xFFF2F2F7),
        fill = if (dark) Color.White.copy(alpha = 0.10f) else ink.copy(alpha = 0.05f),
        segOn = if (dark) Color(0xFF3A3A40) else Color.White,
        segShadow = !dark,
        primary = accent,
        primarySoft = accent.copy(alpha = if (dark) 0.22f else 0.12f),
        primaryInk = if (dark) Color(0xFFCFE4FF) else Color(0xFF0A66C2),
        divider = if (dark) Color.White.copy(alpha = 0.08f) else ink.copy(alpha = 0.07f),
        dash = if (dark) Color.White.copy(alpha = 0.18f) else ink.copy(alpha = 0.16f),
        ok = Color(0xFF34C759),
        okSoft = Color(0xFF34C759).copy(alpha = if (dark) 0.16f else 0.1f),
        okInk = if (dark) Color(0xFF8BE0A6) else Color(0xFF1B8A3C),
        warn = Color(0xFFFF9F0A),
        warnSoft = Color(0xFFFF9F0A).copy(alpha = if (dark) 0.18f else 0.12f),
        warnInk = if (dark) Color(0xFFFFD08A) else Color(0xFF9A5F00),
        err = Color(0xFFFF453A),
        errSoft = Color(0xFFFF453A).copy(alpha = if (dark) 0.18f else 0.1f),
        ringBorder = if (dark) Color(0xFF1A1B20) else Color.White,
        // MiuiX 卡片：默认实色，只有开启「卡片透明」时才有一层极淡的高光渐变
        card = if (dark) Brush.linearGradient(
            0f to Color(0xFF232329),
            0.55f to Color(0xFF1E1E22),
            1f to Color(0xFF212127)
        ) else Brush.linearGradient(
            0f to Color.White,
            0.55f to Color(0xFFFCFCFE),
            1f to Color.White
        ),
        cardFlat = if (dark) Color(0xFF1E1E22) else Color.White,
        border = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f),
        rim = if (dark) SolidColor(Color.White.copy(alpha = 0.07f))
        else SolidColor(Color.Black.copy(alpha = 0.04f)),
        // HyperOS 不在卡片顶部画内高光，这里保留字段但压到不可见
        highlight = Color.Transparent,
        shadowColor = if (dark) Color.Black.copy(alpha = 0.30f) else Color(0xFF6B7280).copy(alpha = 0.10f),
        bar = if (dark) SolidColor(Color(0xFF141416)) else SolidColor(Color(0xFFF7F7F7)),
        barBorder = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f),
        sheet = if (dark) Color(0xFF1E1E22) else Color.White,
        glowBlue = accent.copy(alpha = if (dark) 0.20f else 0.14f),
        glowOrange = Color(0xFFFF9A5A).copy(alpha = if (dark) 0.10f else 0.10f),
        glowPurple = Color(0xFF8C6EFF).copy(alpha = if (dark) 0.12f else 0.08f),
        orbAlpha = if (dark) 0.35f else 0.4f,
        barSolid = if (dark) Color(0xFF141416) else Color(0xFFF7F7F7),
    )
    if (glass) return base
    // 关闭「卡片透明」：完全不透明实色卡片 + 纯色背��，即 MiuiX 的标准观感
    val solid = if (dark) Color(0xFF1E1E22) else Color.White
    return base.copy(
        bgBase = if (dark) Color(0xFF0F0F11) else Color(0xFFF2F2F7),
        card = SolidColor(solid),
        cardFlat = solid,
        border = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f),
        rim = SolidColor(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)),
        highlight = Color.Transparent,
        shadowColor = Color.Transparent,
        bar = SolidColor(if (dark) Color(0xFF141416) else Color(0xFFF7F7F7)),
        barBorder = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f),
        sheet = solid,
        orbAlpha = 0f,
    )
}

val LocalGlassTokens = staticCompositionLocalOf { glassTokens(dark = false, glass = true) }
