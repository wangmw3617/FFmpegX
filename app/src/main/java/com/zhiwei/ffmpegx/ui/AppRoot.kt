package com.zhiwei.ffmpegx.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.ui.nav.AudioRoute
import com.zhiwei.ffmpegx.ui.nav.CompressRoute
import com.zhiwei.ffmpegx.ui.nav.ConcatRoute
import com.zhiwei.ffmpegx.ui.nav.ConsoleRoute
import com.zhiwei.ffmpegx.ui.nav.ConvertRoute
import com.zhiwei.ffmpegx.ui.nav.GifRoute
import com.zhiwei.ffmpegx.ui.nav.HomeRoute
import com.zhiwei.ffmpegx.ui.nav.OverlayRoute
import com.zhiwei.ffmpegx.ui.nav.ProbeRoute
import com.zhiwei.ffmpegx.ui.nav.QueueRoute
import com.zhiwei.ffmpegx.ui.nav.RawCommandRoute
import com.zhiwei.ffmpegx.ui.nav.SettingsRoute
import com.zhiwei.ffmpegx.ui.nav.SubtitleRoute
import com.zhiwei.ffmpegx.ui.nav.TrimRoute
import com.zhiwei.ffmpegx.ui.screen.ConsoleScreen
import com.zhiwei.ffmpegx.ui.screen.HomeScreen
import com.zhiwei.ffmpegx.ui.screen.ProbeScreen
import com.zhiwei.ffmpegx.ui.screen.QueueScreen
import com.zhiwei.ffmpegx.ui.screen.SettingsScreen
import com.zhiwei.ffmpegx.ui.screen.TasksViewModel
import com.zhiwei.ffmpegx.ui.tool.AudioScreen
import com.zhiwei.ffmpegx.ui.tool.CompressScreen
import com.zhiwei.ffmpegx.ui.tool.ConcatScreen
import com.zhiwei.ffmpegx.ui.tool.ConsoleToolScreen
import com.zhiwei.ffmpegx.ui.tool.ConvertScreen
import com.zhiwei.ffmpegx.ui.tool.GifScreen
import com.zhiwei.ffmpegx.ui.tool.OverlayScreen
import com.zhiwei.ffmpegx.ui.tool.SubtitleScreen
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

@Composable
fun AppRoot(settings: AppSettings) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isTopLevel = TOP_LEVEL_TABS.any { destination?.hasRoute(it.route::class) == true }
    val title = titleFor(destination)

    // Android 13+ 需要显式申请通知权限，否则前台服务的进度通知不会显示
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户拒绝也不影响功能，只是看不到通知 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            if (isTopLevel) {
                TopLevelBottomBar(navController = navController, destination = destination)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding),
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
            composable<ProbeRoute> { ProbeScreen() }
            composable<RawCommandRoute> { ConsoleToolScreen() }
        }
    }
}

@Composable
private fun TopLevelBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
) {
    val tasksViewModel: TasksViewModel = hiltViewModel()
    val activeCount by tasksViewModel.activeCount.collectAsStateWithLifecycle()

    NavigationBar {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = destination?.hasRoute(tab.route::class) == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(HomeRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    if (tab.route == QueueRoute && activeCount > 0) {
                        BadgedBox(badge = { Badge { Text(activeCount.toString()) } }) {
                            Icon(tab.icon, contentDescription = tab.label)
                        }
                    } else {
                        Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label) },
            )
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
    destination.hasRoute(ProbeRoute::class) -> "媒体信息"
    destination.hasRoute(RawCommandRoute::class) -> "命令行"
    else -> "FFmpegX"
}
