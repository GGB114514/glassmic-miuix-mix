package io.mo.glassmic.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.roundToPx

/** Applies a minimum height when the standard foundation heightIn extension is not imported. */
internal fun Modifier.heightIn(min: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val minHeightPx = min.roundToPx().coerceAtMost(constraints.maxHeight)
        val placeable = measurable.measure(
            constraints.copy(minHeight = maxOf(constraints.minHeight, minHeightPx))
        )
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
)
