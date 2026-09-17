package com.zhiwei.ffmpegx.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.ui.nav.AudioRoute
import com.zhiwei.ffmpegx.ui.nav.CompressRoute
import com.zhiwei.ffmpegx.ui.nav.ConcatRoute
import com.zhiwei.ffmpegx.ui.nav.ConsoleRoute
import com.zhiwei.ffmpegx.ui.nav.ConvertRoute
import com.zhiwei.ffmpegx.ui.nav.CropRoute
import com.zhiwei.ffmpegx.ui.nav.DelogoRoute
import com.zhiwei.ffmpegx.ui.nav.GifRoute
import com.zhiwei.ffmpegx.ui.nav.HomeRoute
import com.zhiwei.ffmpegx.ui.nav.OverlayRoute
import com.zhiwei.ffmpegx.ui.nav.ProbeRoute
import com.zhiwei.ffmpegx.ui.nav.QueueRoute
import com.zhiwei.ffmpegx.ui.nav.RawCommandRoute
import com.zhiwei.ffmpegx.ui.nav.RotateRoute
import com.zhiwei.ffmpegx.ui.nav.SettingsRoute
import com.zhiwei.ffmpegx.ui.nav.SlideshowRoute
import com.zhiwei.ffmpegx.ui.nav.SpeedRoute
import com.zhiwei.ffmpegx.ui.nav.SubtitleRoute
import com.zhiwei.ffmpegx.ui.nav.ThumbnailRoute
import com.zhiwei.ffmpegx.ui.nav.TrimRoute
import com.zhiwei.ffmpegx.ui.screen.ConsoleScreen
import com.zhiwei.ffmpegx.ui.screen.HomeScreen
import com.zhiwei.ffmpegx.ui.screen.ProbeScreen
import com.zhiwei.ffmpegx.ui.screen.QueueScreen
import com.zhiwei.ffmpegx.ui.screen.SettingsScreen
import com.zhiwei.ffmpegx.ui.screen.TasksViewModel
import com.zhiwei.ffmpegx.ui.theme.AppBackground
import com.zhiwei.ffmpegx.ui.theme.liquidGlass
import com.zhiwei.ffmpegx.ui.theme.rememberAppBackdrop
import com.zhiwei.ffmpegx.ui.tool.AudioScreen
import com.zhiwei.ffmpegx.ui.tool.CompressScreen
import com.zhiwei.ffmpegx.ui.tool.ConcatScreen
import com.zhiwei.ffmpegx.ui.tool.ConsoleToolScreen
import com.zhiwei.ffmpegx.ui.tool.ConvertScreen
import com.zhiwei.ffmpegx.ui.tool.CropScreen
import com.zhiwei.ffmpegx.ui.tool.DelogoScreen
import com.zhiwei.ffmpegx.ui.tool.GifScreen
import com.zhiwei.ffmpegx.ui.tool.OverlayScreen
import com.zhiwei.ffmpegx.ui.tool.RotateScreen
import com.zhiwei.ffmpegx.ui.tool.SlideshowScreen
import com.zhiwei.ffmpegx.ui.tool.SpeedScreen
import com.zhiwei.ffmpegx.ui.tool.SubtitleScreen
import com.zhiwei.ffmpegx.ui.tool.ThumbnailScreen
import com.zhiwei.ffmpegx.ui.tool.TrimScreen

private data class TopLevelTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab("首页", Icons.Default.Home, HomeRoute),
    TopLevelTab("队列", Icons.AutoMirrored.Filled.List, QueueRoute),
    TopLevelTab("命令", Icons.Default.Terminal, ConsoleRoute),
    TopLevelTab("设置", Icons.Default.Settings, SettingsRoute),
)

/** 顶栏/底栏的内容高度（不含系统栏内边距） */
private val TOP_BAR_HEIGHT = 56.dp
private val BOTTOM_BAR_HEIGHT = 64.dp

/**
 * 顶栏占位。
 *
 * 采样层里用它把内容让开，玻璃条则叠在它上面 —— 两者尺寸修饰符必须
 * 完全一致，否则内容会被压住或露出空隙。所以这里和 [GlassTopBar] 共用
 * 同一组常量与同一套 `windowInsetsPadding + height` 组合。
 *
 * 必须标 `@Composable`：`WindowInsets.statusBars` 是 `@Composable`
 * `@ReadOnlyComposable` 的取值器，在普通函数里读不到。
 */
@Composable
private fun Modifier.topBarSlot(): Modifier =
    fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(TOP_BAR_HEIGHT)

@Composable
private fun Modifier.bottomBarSlot(): Modifier =
    fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(BOTTOM_BAR_HEIGHT)

@Composable
fun AppRoot(settings: AppSettings) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isTopLevel = TOP_LEVEL_TABS.any { destination?.hasRoute(it.route::class) == true }
    val title = titleFor(destination)

    val backdrop = rememberAppBackdrop()

    // Android 13+ 需要显式申请通知权限，否则前台服务的进度通知不会显示
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户拒绝也不影响功能，只是看不到通知 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ① 采样层：背景色斑 + 全部页面内容。玻璃条从这里取像素。
        AppBackground(backdrop) {
            Column(Modifier.fillMaxSize()) {
                // 给顶栏让位。内容不进玻璃条底下 —— 工具页表单密集，
                // 被玻璃压住会挡住输入框，所以玻璃只糊背景色斑。
                Spacer(Modifier.topBarSlot())

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f),
                ) {
                    composable<HomeRoute> {
                        HomeScreen(onOpenTool = { route -> navController.navigate(route) })
                    }
                    composable<QueueRoute> { QueueScreen() }
                    composable<ConsoleRoute> { ConsoleScreen() }
                    composable<SettingsRoute> { SettingsScreen() }

                    composable<ConvertRoute> { ConvertScreen() }
                    composable<CompressRoute> { CompressScreen() }
                    composable<TrimRoute> { TrimScreen() }
                    composable<AudioRoute> { AudioScreen() }
                    composable<GifRoute> { GifScreen() }
                    composable<ConcatRoute> { ConcatScreen() }
                    composable<SubtitleRoute> { SubtitleScreen() }
                    composable<OverlayRoute> { OverlayScreen() }
                    composable<RotateRoute> { RotateScreen() }
                    composable<CropRoute> { CropScreen() }
                    composable<ThumbnailRoute> { ThumbnailScreen() }
                    composable<SpeedRoute> { SpeedScreen() }
                    composable<DelogoRoute> { DelogoScreen() }
                    composable<SlideshowRoute> { SlideshowScreen() }
                    composable<ProbeRoute> { ProbeScreen() }
                    composable<RawCommandRoute> { ConsoleToolScreen() }
                }

                if (isTopLevel) Spacer(Modifier.bottomBarSlot())
            }
        }

        // ② 玻璃层。必须是采样层的**兄弟**且排在后面：被包在采样层里的话，
        //    玻璃采到的是自己，会递归成一团糊。
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = title,
                showBack = !isTopLevel,
                onBack = { navController.popBackStack() },
                backdrop = backdrop,
            )
            Spacer(Modifier.weight(1f))
            if (isTopLevel) {
                GlassBottomBar(
                    navController = navController,
                    destination = destination,
                    backdrop = backdrop,
                )
            }
        }
    }
}

/**
 * 玻璃顶栏。
 *
 * 尺寸修饰符与 [topBarSlot] 保持一致；`liquidGlass` 写在 `windowInsetsPadding`
 * 之前，这样玻璃覆盖的是「状态栏 + 内容高度」整块区域，而文字仍然避开状态栏。
 */
@Composable
private fun GlassTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(backdrop, RectangleShape)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TOP_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(20.dp))
    }
}

/**
 * 玻璃底栏。
 *
 * 顶角做了圆角，让玻璃的折射边缘露出来 —— 直角矩形的边缘折射看不见。
 * 选中态沿用主题主色，未选中用 onSurfaceVariant。
 */
@Composable
private fun GlassBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tasksViewModel: TasksViewModel = hiltViewModel()
    val activeCount by tasksViewModel.activeCount.collectAsStateWithLifecycle()

    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(backdrop, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BOTTOM_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = destination?.hasRoute(tab.route::class) == true
            val tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) {
                        if (!selected) {
                            navController.navigate(tab.route) {
                                popUpTo(HomeRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (tab.route == QueueRoute && activeCount > 0) {
                    BadgedBox(badge = { Badge { Text(activeCount.toString()) } }) {
                        Icon(tab.icon, contentDescription = tab.label, tint = tint)
                    }
                } else {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint)
                }
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                )
            }
        }
    }
}

private fun titleFor(destination: NavDestination?): String = when {
    destination == null -> "FFmpegX"
    destination.hasRoute(HomeRoute::class) -> "FFmpegX"
    destination.hasRoute(QueueRoute::class) -> "任务队列"
    destination.hasRoute(ConsoleRoute::class) -> "运行日志"
    destination.hasRoute(SettingsRoute::class) -> "设置"
    destination.hasRoute(ConvertRoute::class) -> "格式转换"
    destination.hasRoute(CompressRoute::class) -> "视频压缩"
    destination.hasRoute(TrimRoute::class) -> "剪辑截取"
    destination.hasRoute(AudioRoute::class) -> "音频处理"
    destination.hasRoute(GifRoute::class) -> "GIF 制作"
    destination.hasRoute(ConcatRoute::class) -> "视频拼接"
    destination.hasRoute(SubtitleRoute::class) -> "字幕处理"
    destination.hasRoute(OverlayRoute::class) -> "水印与画中画"
    // 以下 6 个是后加的工具，原先漏在这里，导致顶栏标题退化成 "FFmpegX"
    destination.hasRoute(RotateRoute::class) -> "旋转翻转"
    destination.hasRoute(CropRoute::class) -> "画面裁剪"
    destination.hasRoute(ThumbnailRoute::class) -> "提取画面"
    destination.hasRoute(SpeedRoute::class) -> "视频变速"
    destination.hasRoute(DelogoRoute::class) -> "去水印"
    destination.hasRoute(SlideshowRoute::class) -> "图片转视频"
    destination.hasRoute(ProbeRoute::class) -> "媒体信息"
    destination.hasRoute(RawCommandRoute::class) -> "命令行"
    else -> "FFmpegX"
}
