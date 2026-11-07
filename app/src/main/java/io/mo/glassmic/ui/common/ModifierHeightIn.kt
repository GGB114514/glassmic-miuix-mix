package io.mo.glassmic.ui.common

import androidx.compose.foundation.layout.heightIn as foundationHeightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Package-local bridge to Compose Foundation's heightIn modifier. */
internal fun Modifier.heightIn(
    min: Dp = Dp.Unspecified,
    max: Dp = Dp.Unspecified
): Modifier = foundationHeightIn(min, max)
