package io.mo.glassmic.data.runtime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionStatus { GRANTED, DENIED, UNKNOWN }

data class PermissionState(
    val root: PermissionStatus = PermissionStatus.UNKNOWN,
    val notification: PermissionStatus = PermissionStatus.UNKNOWN,
    val overlay: PermissionStatus = PermissionStatus.UNKNOWN,
    val fileAccess: PermissionStatus = PermissionStatus.UNKNOWN,
    val foregroundService: PermissionStatus = PermissionStatus.UNKNOWN,
    /**
     * 麦克风运行时授权。混音功能（音乐 + 真人麦克风）依赖它，
     * 但常规注入不依赖，因此不计入 [allGranted]，只在混音入口上做门禁提示。
     */
    val mic: PermissionStatus = PermissionStatus.UNKNOWN,
    val androidVersionSupported: Boolean = true,
    val safeModeOk: Boolean = true
) {
    val allGranted: Boolean get() = listOf(
        root, notification, overlay, fileAccess, foregroundService
    ).all { it == PermissionStatus.GRANTED } && androidVersionSupported && safeModeOk

    /** 混音态可用的前提：麦克风已授权。 */
    val micReadyForMix: Boolean get() = mic == PermissionStatus.GRANTED
}

@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safeModeRepo: SafeModeRepository
) {

    fun checkAll(): PermissionState = PermissionState(
        root = checkRoot(),
        notification = checkNotification(),
        overlay = checkOverlay(),
        fileAccess = checkFileAccess(),
        foregroundService = checkForegroundService(),
        mic = checkMic(),
        androidVersionSupported = Build.VERSION.SDK_INT >= 29,
        safeModeOk = !safeModeRepo.isActive()
    )

    // ============ Root ============
    fun checkRoot(): PermissionStatus {
        // 静默检测 su 二进制；若用户拒绝授予则会失败
        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use {
                it.writeBytes("id\nexit\n")
                it.flush()
            }
            val exit = process.waitFor()
            if (exit == 0) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }.getOrDefault(PermissionStatus.DENIED)
    }

    // ============ 通知 ============
    fun checkNotification(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        // 旧版默认有，但 channel 可能被关
        return PermissionStatus.GRANTED
    }

    // ============ 悬浮窗 ============
    fun checkOverlay(): PermissionStatus =
        if (Settings.canDrawOverlays(context)) PermissionStatus.GRANTED
        else PermissionStatus.DENIED

    // ============ 文件访问 ============
    fun checkFileAccess(): PermissionStatus {
        // Android 13+ READ_MEDIA_AUDIO
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        // API 29-32: READ_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    // ============ 前台服务能力 ============
    fun checkForegroundService(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= 34) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        return PermissionStatus.GRANTED
    }

    // ============ 麦克风（混音功能需要）============
    /**
     * RECORD_AUDIO 在 Manifest 里早就声明了，但此前全流程没有做过运行时申请。
     * 混音态必须由 GlassMic 自己采集真实麦克风（应用侧引擎），所以补上这一项检测。
     */
    fun checkMic(): PermissionStatus =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) PermissionStatus.GRANTED else PermissionStatus.DENIED

    /** 被拒但尚未「永久拒绝」时返回 true——此时还可以直接再弹一次系统授权框。 */
    fun shouldShowMicRationale(activity: Activity): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    /** 永久拒绝后只能引导用户去应用详情页手动开启。 */
    fun micSettingsIntent(): android.content.Intent = android.content.Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${Constants.APP_PACKAGE}")
    )

    fun overlaySettingsIntent() =
        android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${Constants.APP_PACKAGE}")
        )

    fun appNotificationSettingsIntent() =
        android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, Constants.APP_PACKAGE)
        }
}
