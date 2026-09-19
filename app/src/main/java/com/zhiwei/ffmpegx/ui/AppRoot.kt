package com.zhiwei.ffmpegx.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import com.zhiwei.ffmpegx.ui.nav.WebDavRoute
import com.zhiwei.ffmpegx.ui.screen.ConsoleScreen
import com.zhiwei.ffmpegx.ui.screen.HomeScreen
import com.zhiwei.ffmpegx.ui.screen.ProbeScreen
import com.zhiwei.ffmpegx.ui.screen.QueueScreen
import com.zhiwei.ffmpegx.ui.screen.SettingsScreen
import com.zhiwei.ffmpegx.ui.screen.TasksViewModel
import com.zhiwei.ffmpegx.ui.screen.WebDavScreen
import com.zhiwei.ffmpegx.ui.theme.AppBackground
import com.zhiwei.ffmpegx.ui.theme.AppContent
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
        // ① 采样层：**只有背景色斑，不含页面内容**。
        //
        // 这里曾经把整屏内容一起纳进来（理由是「内容滚到玻璃下面时玻璃能真的
        // 把它糊掉」），代价是任何滚动/动画都会触发一次整屏图层重录 + 全屏模糊
        // 重采样 —— 那是全局卡顿的头号来源。上游 catalog 的采样层只包一张静态
        // 图，我们这里退一步：只采样底色与色斑。详见 LiquidGlass.kt 的文件头。
        AppBackground(backdrop)

        // ② 内容层：页面内容，**不参与采样**。
        //
        // 内容**铺满整屏**，不给底栏预留位置 —— 各一级页面自己在内容末尾留出
        // BottomBarReserve，避免最后一项被悬浮底栏挡住。
        AppContent {
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
                    composable<SettingsRoute> {
                        SettingsScreen(onOpenWebDav = { navController.navigate(WebDavRoute) })
                    }

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
                    composable<WebDavRoute> { WebDavScreen() }
                }
            }
        }

        // ③ 玻璃层。必须是采样层的**兄弟**且排在后面：被包在采样层里的话，
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

    // 按压进度：任一标签被按下时升到 1。上游做法是每帧跟随手指位置做
    // 高光与缩放（InteractiveHighlight + DampedDragAnimation），代价是要自己
    // 接管手势。这里取一个更轻的等效：按住时把整条玻璃「压一下」——
    // 内投影变深、外投影收紧、折射收起，松手回弹。
    //
    // 为什么值得做：玻璃是「有厚度」的材质，没有按压反馈时，点标签的触感
    // 全靠 Material 涟漪；而涟漪在玻璃上会被高光冲淡，用户会觉得「点了没反应」。
    var pressed by remember { mutableStateOf(false) }
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "barPress",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // 修饰符顺序有讲究：先避开手势条，再留出悬浮的空白，
            // 最后才是 liquidGlass —— 它写在内层，玻璃只覆盖底栏本身，
            // 不会把外面的留白也涂上。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = BOTTOM_BAR_SIDE_MARGIN, vertical = BOTTOM_BAR_MARGIN)
            .height(BOTTOM_BAR_HEIGHT)
            .graphicsLayer {
                // 按压缩放：幅度很小（0.4%），只做「手感」不做「形变」
                val scale = 1f - 0.004f * pressProgress
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(26.dp),
                // 参数对照上游 catalog/components/LiquidBottomTabs.kt：
                // 底栏本体 blur 8dp / lens 24dp，并叠一层 Highlight。
                // 早先这里 blur 给到 22dp（上游的两倍多），把背景色斑糊成一片均色，
                // 折射与高光都失去参照 —— 那是「不如原项目」的直接原因。
                blurRadius = 8.dp,
                lensAmount = 24.dp,
                withHighlight = true,
                // 悬浮元素加投影才有「浮在内容之上」的立体感，这是上游靠
                // Shadow(alpha = progress) 表达的东西；我们不按压时也保留一点。
                withShadow = true,
                // 按住时压出内投影：玻璃被「按薄」了，边缘出现一圈暗部
                withInnerShadow = true,
                innerShadowAlpha = 0.12f + 0.3f * pressProgress,
                pressProgress = pressProgress,
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
                        // 指示器比底栏「薄」一档：模糊更浅、折射更小，
                        // 它才像一片浮在底栏之上的小透镜，而不是第二层厚板。
                        blurRadius = 6.dp,
                        lensAmount = 18.dp,
                        withHighlight = true,
                        withInnerShadow = true,
                        innerShadowAlpha = 0.35f,
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
                    onPressChange = { down -> pressed = down },
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
    onPressChange: (Boolean) -> Unit,
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

        // ② 按压探针：只驱动视觉动画，不拦截事件（不消费 change），
        //    所以它不影响上面 clickable 的判定。
        //
        //    ⚠️ 这里**必须**用 `awaitEachGesture` + `waitForUpOrCancellation`，
        //    不能写成 `while (true) { awaitPointerEvent() }`：
        //    后者只在收到事件时读 `it.pressed`，一旦手势被取消（手指滑出该
        //    Tab 范围、父层抢走事件、系统返回手势介入），就再也不会有后续事件
        //    送进来，`pressed` 会永久停在 true —— 表现为底栏一直保持着按下态。
        //    `waitForUpOrCancellation()` 在「抬起」和「取消」两条路径上都会返回，
        //    因此 `finally` 里的复位一定会执行。
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = (BOTTOM_BAR_HEIGHT - INDICATOR_HEIGHT) / 2)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 不能 consume：本探针与下面的 clickable 是兄弟节点，
                        // 一旦把 down 事件标记为已消费，clickable 就收不到点击了。
                        // requireUnconsumed = false 也是同理 —— 要能读到已被
                        // 其他识别器处理过的事件，只做「观察者」。
                        awaitFirstDown(requireUnconsumed = false)
                        onPressChange(true)
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            // 抬起 / 取消都会走到这里，保证状态复位
                            onPressChange(false)
                        }
                    }
                },
        )

        // ③ 内容层：不参与裁剪，角标可以正常探出
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
    destination.hasRoute(WebDavRoute::class) -> "WebDAV 文件"
    else -> "FFmpegX"
}
