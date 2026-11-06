package io.mo.glassmic.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MiuiX（HyperOS）底部导航栏。
 *
 * 与原来的浮动玻璃胶囊不同，HyperOS 的底栏是**贴底通栏**：
 * 一条很浅的分割线 + 均分的图标/文字对，选中项用强调色，未选中用中性灰。
 * 指示点由系统手势条承担，App 不再自己画选中块，避免和系统导航条打架。
 */
data class MiuixTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

@Composable
fun MiuixBottomBar(
    tabs: List<MiuixTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = glass
    Box(
        modifier = modifier
            .fillMaxWidth()
            // HyperOS 底栏很克制：只有一层极淡的底色和一条顶部分割线
            .background(t.barSolid)
            .padding(top = 2.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(t.divider)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            tabs.forEachIndexed { index, tab ->
                MiuixTabItem(
                    tab = tab,
                    selected = index == selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.MiuixTabItem(
    tab: MiuixTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t = glass
    val color = if (selected) t.primary else t.ink3
    Column(
        modifier = modifier
            .liquidClickable(pressed = 0.94f, onClick = onClick)
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = color,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = tab.label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * HyperOS 分组标题：小一号的次级灰字，左侧与卡片对齐。
 * MiuiX 的设置页用它切分「网络」「声音」这类分组。
 */
@Composable
fun MiuixSectionTitle(text: String, modifier: Modifier = Modifier) {
    val t = glass
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = t.ink3
    )
}

/** 页面大标题——HyperOS 首页那种 34sp 粗标题，紧贴状态栏下方。 */
@Composable
fun MiuixPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
        color = glass.ink
    )
}

/** 混音状态徽标——三态与采集状态在主页一眼可辨。 */
@Composable
fun MiuixStatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val t = glass
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.chipRadius))
            .background(color.copy(alpha = if (t.isDark) 0.18f else 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
