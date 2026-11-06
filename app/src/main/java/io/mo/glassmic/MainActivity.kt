package io.mo.glassmic

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import io.mo.glassmic.ui.theme.LocalGlassTokens
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import io.mo.glassmic.ui.common.MiuixBottomBar
import io.mo.glassmic.ui.common.MiuixTab
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.ui.common.frosted
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import io.mo.glassmic.ui.common.LocalSharedBackdrop
import io.mo.glassmic.ui.common.rememberLiquidSpan
import kotlin.math.roundToInt
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import io.mo.glassmic.ui.common.GlassBackground
import io.mo.glassmic.ui.common.liquidClickable
import io.mo.glassmic.ui.common.rememberLiquidEdges
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import io.mo.glassmic.ui.common.SplashOverlay
import io.mo.glassmic.ui.theme.LocalReduceMotion
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.ui.diag.DiagnosticScreen
import io.mo.glassmic.ui.home.HomeScreen
import io.mo.glassmic.ui.library.LibraryScreen
import io.mo.glassmic.ui.onboarding.OnboardingFlow
import io.mo.glassmic.ui.scope.ScopeScreen
import io.mo.glassmic.ui.settings.AiTtsSettingsScreen
import io.mo.glassmic.ui.settings.SettingsScreen
import io.mo.glassmic.ui.settings.SafeModeScreen
import io.mo.glassmic.ui.theme.GlassMicTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // MainActivity 不是 AppCompatActivity，Android 13 以下需要在这里手动按已保存的语言包一层
    // Context，AppCompatDelegate.setApplicationLocales() 才能在冷启动时真正生效。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassMicTheme {
                // 状态栏 / 导航栏图标跟随 App 自己的深浅色，而不是系统设置
                val dark = LocalGlassTokens.current.isDark
                val windowBg = LocalGlassTokens.current.bgBase
                SideEffect {
                    // 窗口底色设为页面底色，避免任何透明瞬间露出系统默认的白底
                    window.setBackgroundDrawable(ColorDrawable(windowBg.toArgb()))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val vm: GateViewModel = hiltViewModel()
                val gate by vm.gate.collectAsState()
                val nav = rememberNavController()
                // 开屏动画只在冷启动播放；系统“降低动画”时跳过
                val reduceMotion = LocalReduceMotion.current
                var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                // 导航外层再垫一层同样的玻璃背景：页面淡入淡出时露出的是同一张背景而不是白底
                GlassBackground {
                    AppNavHost(nav, gate)
                    if (showSplash && !reduceMotion) SplashOverlay(onFinished = { showSplash = false })
                }
            }
        }
    }
}

/** 全局门禁——决定启动后第一屏 */
data class GateDecision(
    val loading: Boolean = true,
    val safeMode: Boolean = false,
    val onboardingCompleted: Boolean = false
)

@HiltViewModel
class GateViewModel @Inject constructor(
    configStore: ConfigStore,
    safeModeRepo: SafeModeRepository
) : ViewModel() {

    private val _gate = MutableStateFlow(GateDecision())
    val gate: StateFlow<GateDecision> = _gate.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = configStore.current()
            _gate.value = GateDecision(
                loading = false,
                safeMode = safeModeRepo.isActive(),
                onboardingCompleted = cfg.onboardingCompleted
            )
        }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    /** 主界面：麦克风 / 音频库 / 设置三个标签页，左右滑动切换。 */
    const val MAIN = "main"
    const val SCOPE = "scope"
    const val AI_TTS = "ai_tts"
    const val SAFE_MODE = "safemode"
    const val DIAGNOSTICS = "diagnostics"
}

private data class TabSpec(val label: Int, val icon: ImageVector)

/**
 * 底部四栏：主页 / 应用列表 / 音乐 / 设置。
 *
 * 「应用列表」原来是设置里的二级页，现在提升为一等标签——目标应用选择是
 * 使用频率最高的操作之一，藏在二级页里每次都要多点两下。
 */
private val TABS = listOf(
    TabSpec(R.string.tab_home, Icons.Rounded.Home),
    TabSpec(R.string.tab_apps, Icons.Rounded.Apps),
    TabSpec(R.string.tab_music, Icons.Rounded.MusicNote),
    TabSpec(R.string.tab_settings, Icons.Rounded.Tune)
)

private const val PAGE_HOME = 0
private const val PAGE_APPS = 1
private const val PAGE_MUSIC = 2
private const val PAGE_SETTINGS = 3

@Composable
private fun AppNavHost(nav: NavHostController, gate: GateDecision) {
    if (gate.loading) return

    // 首屏决策——严格遵循需求 §11 优先级
    val start = when {
        gate.safeMode -> Routes.SAFE_MODE
        !gate.onboardingCompleted -> Routes.ONBOARDING
        else -> Routes.MAIN
    }

    val reduceMotion = LocalReduceMotion.current
    NavHost(
        nav,
        startDestination = start,
        // 进入二级页用 iOS 式右侧推入 + 底页视差；标签之间的切换在 MainPager 里左右滑动完成。
        enterTransition = {
            if (reduceMotion) EnterTransition.None
            else slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
        },
        exitTransition = {
            if (reduceMotion) ExitTransition.None
            else slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 } +
                fadeOut(tween(320), targetAlpha = 0.6f)
        },
        // 返回时底页从 60% 亮度渐渐恢复，与进入时的变暗对称
        popEnterTransition = {
            if (reduceMotion) EnterTransition.None
            else slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 } +
                fadeIn(tween(320), initialAlpha = 0.6f)
        },
        popExitTransition = {
            if (reduceMotion) ExitTransition.None
            else slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
        }
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingFlow(onCompleted = {
                nav.navigate(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.MAIN) {
            MainPager(
                onOpenScope = { nav.navigate(Routes.SCOPE) },
                onOpenAiTts = { nav.navigate(Routes.AI_TTS) },
                onOpenDiagnostic = { nav.navigate(Routes.DIAGNOSTICS) }
            )
        }
        composable(Routes.SCOPE) { ScopeScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.AI_TTS) { AiTtsSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DIAGNOSTICS) { DiagnosticScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SAFE_MODE) {
            SafeModeScreen(onExitComplete = {
                nav.navigate(Routes.MAIN) {
                    popUpTo(Routes.SAFE_MODE) { inclusive = true }
                }
            })
        }
    }
}

/**
 * 四个标签页：左右滑动切换，点底栏也是滑过去。
 * 背景由外层统一绘制、保持不动（[LocalSharedBackdrop]），滑动时只有页面内容移动。
 */
@Composable
private fun MainPager(
    onOpenScope: () -> Unit,
    onOpenAiTts: () -> Unit,
    onOpenDiagnostic: () -> Unit
) {
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val goTo: (Int) -> Unit = { page ->
        scope.launch { if (reduceMotion) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    // 返回键：不在主页时先回到主页，再按才退出
    BackHandler(enabled = pager.currentPage != PAGE_HOME) { goTo(PAGE_HOME) }

    val tabs = TABS.map { MiuixTab(stringResource(it.label), it.icon) }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedBackdrop provides true) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = TABS.size - 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    PAGE_HOME -> HomeScreen(
                        onOpenLibrary = { goTo(PAGE_MUSIC) },
                        // 应用列表已是一等标签，不再往二级页里钻
                        onOpenScope = { goTo(PAGE_APPS) },
                        onOpenDiagnostic = onOpenDiagnostic
                    )
                    PAGE_APPS -> ScopeScreen(onBack = {}, asTab = true)
                    PAGE_MUSIC -> LibraryScreen()
                    else -> SettingsScreen(
                        onOpenScope = { goTo(PAGE_APPS) },
                        onOpenAiTts = onOpenAiTts,
                        onOpenDiagnostic = onOpenDiagnostic
                    )
                }
            }
        }
        // MiuiX 底栏：贴底通栏，不再浮动
        MiuixBottomBar(
            tabs = tabs,
            selected = pager.currentPage,
            onSelect = goTo,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

/**
 * 浮动玻璃底栏已被 [MiuixBottomBar] 取代——MiuiX 的底栏贴底通栏、不浮动，
 * 选中态只靠强调色与图标实心变化表达，指示点交给系统手势条。
 */
