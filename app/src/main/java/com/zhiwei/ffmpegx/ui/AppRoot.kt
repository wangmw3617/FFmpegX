package com.zhiwei.ffmpegx.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

/** 悬浮底栏的高度（不含外留白与手势条内边距） */
private val BOTTOM_BAR_HEIGHT = 64.dp
private val BOTTOM_BAR_MARGIN = 10.dp
private val BOTTOM_BAR_SIDE_MARGIN = 14.dp

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
        // ① 采样层：背景色斑 + 全部页面内容。
        //
        // 内容**铺满整屏**，不给底栏预留位置 —— 这样它能滚到悬浮底栏下面，
        // 玻璃才有东西可以模糊。早先是给底栏留了一条空档，玻璃只能糊到
        // 一层背景渐变，模糊前后没有差别，看着就是一块不透明的色板。
        // 各一级页面自己在内容末尾留出 BottomBarReserve，避免最后一项被挡住。
        AppBackground(backdrop) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏不加玻璃效果：容器透明，让背景色斑自然延伸上来，
                // 视觉上更连贯，也不会在顶部压出一块厚重的板。
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )

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
            }
        }

        // ② 玻璃层。必须是采样层的**兄弟**且排在后面：被包在采样层里的话，
        //    玻璃采到的是自己，会递归成一团糊。
        if (isTopLevel) {
            GlassBottomBar(
                navController = navController,
                destination = destination,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 悬浮玻璃底栏。
 *
 * 做成悬浮（左右与底部都留白、四角圆角）而不是通栏贴边：
 * 四周留白能让背景与内容从边上透出来，玻璃的「一片浮在内容之上」的
 * 观感才成立；通栏贴边时玻璃只和屏幕边缘相接，看起来就像一块实心色板。
 */
/** 选中指示器的高度。比槽位矮一档，视觉上才「收」得住。 */
private val INDICATOR_HEIGHT = 44.dp

/** 指示器相对槽位两侧各收进去多少 */
private val INDICATOR_INSET = 14.dp

@Composable
private fun GlassBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tasksViewModel: TasksViewModel = hiltViewModel()
    val activeCount by tasksViewModel.activeCount.collectAsStateWithLifecycle()

    val selectedIndex = TOP_LEVEL_TABS.indexOfFirst {
        destination?.hasRoute(it.route::class) == true
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // 修饰符顺序有讲究：先避开手势条，再留出悬浮的空白，
            // 最后才是 liquidGlass —— 它写在内层，玻璃只覆盖底栏本身，
            // 不会把外面的留白也涂上。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = BOTTOM_BAR_SIDE_MARGIN, vertical = BOTTOM_BAR_MARGIN)
            .height(BOTTOM_BAR_HEIGHT)
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(26.dp),
                blurRadius = 22.dp,
                lensAmount = 12.dp,
            ),
    ) {
        val tabWidth = maxWidth / TOP_LEVEL_TABS.size
        val indicatorShape = RoundedCornerShape(percent = 50)

        // 指示器是**底栏里单独的一条滑动胶囊**，而不是每个槽位各自画一个。
        //
        // 这是「灵动」的关键：切换标签时它带弹性地从一格滑到另一格，有过程；
        // 原先每个槽位自己画自己的，效果是「旧的瞬间消失、新的瞬间出现」——
        // 状态变化没有过程，就只剩一个开关。
        val slide by animateFloatAsState(
            targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
            animationSpec = spring(
                dampingRatio = 0.68f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "indicatorSlide",
        )

        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = tabWidth * slide + INDICATOR_INSET)
                    .width(tabWidth - INDICATOR_INSET * 2)
                    .height(INDICATOR_HEIGHT)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = indicatorShape,
                        blurRadius = 10.dp,
                        lensAmount = 8.dp,
                    ),
            )
        }

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            TOP_LEVEL_TABS.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                GlassTab(
                    tab = tab,
                    selected = selected,
                    badgeCount = if (tab.route == QueueRoute) activeCount else 0,
                    indicatorShape = indicatorShape,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = {
                        if (!selected) {
                            navController.navigate(tab.route) {
                                popUpTo(HomeRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 单个底栏项：只负责内容与点击，不再自己画指示器。
 *
 * ## 为什么把指示器挪出去
 *
 * 指示器原先是每个槽位各自画的一个胶囊，尺寸被「图标 + 文字」撑到几乎占满
 * 整个槽位（约 78×50dp，槽位才 64dp 高）。挪到底栏层做成**一条滑动的胶囊**
 * 之后，它可以比内容更小一圈（44dp 高、两侧各收 14dp），也有了滑动的过程。
 *
 * ## 为什么点击区也要是胶囊
 *
 * Material 的涟漪**按节点形状裁剪**：节点不带形状时按矩形裁，涟漪铺满后
 * 就是一块方角高亮，与整体的圆形语言冲突。所以点击区套上和指示器同一个胶囊。
 *
 * ## 内容为什么放在点击层外面
 *
 * 胶囊会裁掉超出形状的部分，而角标本来就探出图标顶边一点，跟着一起裁就会被
 * 切掉一块。代价是语义要显式补：内容层清空语义、点击层给出描述，
 * 否则 TalkBack 读不到这个标签页叫什么。
 */
@Composable
private fun GlassTab(
    tab: TopLevelTab,
    selected: Boolean,
    badgeCount: Int,
    indicatorShape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // ① 点击层：与指示器同形同高，涟漪不会大出一圈
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = (BOTTOM_BAR_HEIGHT - INDICATOR_HEIGHT) / 2)
                .clip(indicatorShape)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { contentDescription = tab.label },
        )

        // ② 内容层：不参与裁剪，角标可以正常探出
        val tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        // 选中时图标轻微放大 —— 让切换有「回应」，而不只是换了个颜色
        val iconScale by animateFloatAsState(
            targetValue = if (selected) 1f else 0.86f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "iconScale",
        )
        Column(
            modifier = Modifier.clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (badgeCount > 0) {
                BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    )
                }
            } else {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
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
