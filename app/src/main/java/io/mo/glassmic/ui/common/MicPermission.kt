package io.mo.glassmic.ui.common

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.mo.glassmic.data.runtime.PermissionStatus

/**
 * 麦克风运行时授权。
 *
 * 为什么需要它：RECORD_AUDIO 早就写在 Manifest 里，但整个流程从没申请过——
 * 混音要求 GlassMic 自己采集真实麦克风，授权缺失就必须被显式处理，
 * 而不是静默进入"看着在混音、其实没采到声音"的状态。
 *
 * [onResult] 会带着最终授权状态回调，调用方据此刷新采集器与提示。
 */
class MicPermissionState internal constructor(
    val granted: Boolean,
    /** 系统授权框当前是否正在显示——用来避免重复触发。 */
    val requesting: Boolean,
    val launch: () -> Unit,
    /** 永久拒绝（不再询问）时返回 true，UI 应改为引导去系统设置。 */
    val needsSystemSettings: Boolean
)

@Composable
fun rememberMicPermission(onResult: (PermissionStatus) -> Unit = {}): MicPermissionState {
    val context = LocalContext.current
    val activity = context as? Activity
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var requesting by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { allowed ->
        requesting = false
        granted = allowed
        onResult(if (allowed) PermissionStatus.GRANTED else PermissionStatus.DENIED)
    }

    // 用户可能从系统设置页授权后返回，回到前台时重新读一次真实状态
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 被拒绝但还能再问 → 弹系统框；永久拒绝 → 只能去设置页
    val needsSettings = !granted && activity != null &&
        !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.RECORD_AUDIO
        ) && requesting.not() && everRequested(context)

    return MicPermissionState(
        granted = granted,
        requesting = requesting,
        needsSystemSettings = needsSettings,
        launch = {
            if (activity == null) {
                onResult(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            } else if (granted) {
                onResult(PermissionStatus.GRANTED)
            } else {
                requesting = true
                markRequested(context)
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    )
}

private const val FLAG_KEY = "mic_permission_requested"

private fun everRequested(context: android.content.Context): Boolean =
    context.getSharedPreferences("glassmic_ui_state", android.content.Context.MODE_PRIVATE)
        .getBoolean(FLAG_KEY, false)

private fun markRequested(context: android.content.Context) {
    context.getSharedPreferences("glassmic_ui_state", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean(FLAG_KEY, true).apply()
}
