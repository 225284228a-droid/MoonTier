package cn.moonflow.easytier

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var controller: EasyTierController
    private var pendingVpnConfig: String? = null
    private var pendingExportText: String = ""
    private var onImportedConfig: ((NetworkConfig) -> Unit)? = null
    private var onFileMessage: ((String, String) -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnConfig?.let { startVpnServiceWith(it) }
        }
        pendingVpnConfig = null
    }

    private val importTomlLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.mapCatching {
            TomlCodec.parse(it)
        }.onSuccess {
            onImportedConfig?.invoke(it)
        }.onFailure {
            onFileMessage?.invoke("导入失败", it.message ?: "无法读取 TOML")
        }
    }

    private val exportTomlLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/toml")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExportText) }
        }.onSuccess {
            onFileMessage?.invoke("导出完成", "TOML 配置已写入文件")
        }.onFailure {
            onFileMessage?.invoke("导出失败", it.message ?: "无法写入文件")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(applicationContext)
        controller = EasyTierController(applicationContext, store, ::requestVpn)

        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        setContent {
            MoonTierApp(
                store = store,
                controller = controller,
                importToml = { importTomlLauncher.launch(arrayOf("text/*", "application/toml", "application/octet-stream")) },
                exportToml = { config ->
                    pendingExportText = TomlCodec.build(config)
                    exportTomlLauncher.launch("${config.displayName}.toml")
                },
                bindImportHandler = { imported, message ->
                    onImportedConfig = imported
                    onFileMessage = message
                }
            )
        }
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    private fun requestVpn(vpnJson: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingVpnConfig = vpnJson
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnServiceWith(vpnJson)
        }
    }

    private fun startVpnServiceWith(vpnJson: String) {
        val intent = Intent(this, EasyTierVpnService::class.java)
            .putExtra(EasyTierVpnService.EXTRA_CONFIG, vpnJson)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private data class ServerPickerState(
    val title: String,
    val servers: List<ServerEntry>
)

private data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val secondaryButton: Color,
    val text: Color,
    val subText: Color,
    val accent: Color,
    val onAccent: Color,
    val border: Color,
    val success: Color,
    val error: Color,
    val overlay: Color
) {
    companion object {
        val Light = Palette(
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Color(0xFFF7F7F5),
            secondaryButton = Color(0xFFF2F2EF),
            text = Color(0xFF0D0D0D),
            subText = Color(0x850D0D0D),
            accent = Color(0xFF0D0D0D),
            onAccent = Color.White,
            border = Color(0x140D0D0D),
            success = Color(0xFF10A37F),
            error = Color(0xFFD92D20),
            overlay = Color(0x12000000)
        )
        val Dark = Palette(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF171717),
            surfaceVariant = Color(0xFF202020),
            secondaryButton = Color(0xFF2A2A2A),
            text = Color(0xFFF4F4F4),
            subText = Color(0x88F4F4F4),
            accent = Color(0xFFF4F4F4),
            onAccent = Color(0xFF0D0D0D),
            border = Color(0x18FFFFFF),
            success = Color(0xFF19C37D),
            error = Color(0xFFF04438),
            overlay = Color(0x18FFFFFF)
        )
    }
}

@Composable
private fun MoonTierApp(
    store: ConfigStore,
    controller: EasyTierController,
    importToml: () -> Unit,
    exportToml: (NetworkConfig) -> Unit,
    bindImportHandler: (((NetworkConfig) -> Unit, (String, String) -> Unit) -> Unit)
) {
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var configs by remember { mutableStateOf(store.loadConfigs()) }
    var userServers by remember { mutableStateOf(store.loadUserServers()) }
    var officialServers by remember { mutableStateOf(store.loadOfficialServers()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val palette = if (settings.darkMode) Palette.Dark else Palette.Light
    val runtime = controller.state

    fun reloadAll() {
        configs = store.loadConfigs()
        userServers = store.loadUserServers()
        officialServers = store.loadOfficialServers()
        settings = store.loadSettings()
    }

    DisposableEffect(Unit) {
        bindImportHandler(
            { cfg ->
                store.saveConfig(cfg.copy(id = java.util.UUID.randomUUID().toString(), isDefault = configs.isEmpty()))
                reloadAll()
                dialog = "导入完成" to "TOML 配置已加入配置文件列表"
            },
            { title, body -> dialog = title to body }
        )
        onDispose { }
    }

    LaunchedEffect(Unit) {
        if (settings.autoSyncOfficialServers) {
            runCatching { store.syncOfficialServers() }
                .onSuccess { officialServers = store.loadOfficialServers() }
        }
    }

    ApplySystemBars(palette, settings.darkMode)

    MaterialTheme(colorScheme = if (settings.darkMode) darkColorScheme() else lightColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            AnimatedContent(
                targetState = editingConfig,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 12 })
                        .togetherWith(fadeOut(tween(130)) + slideOutHorizontally(tween(180)) { -it / 12 })
                },
                label = "screen"
            ) { editing ->
                if (editing != null) {
                    ConfigEditorScreenV2(
                        palette = palette,
                        initial = editing,
                        userServers = userServers,
                        officialServers = officialServers,
                        onBack = { editingConfig = null },
                        onSave = {
                            store.saveConfig(it)
                            reloadAll()
                            editingConfig = null
                        },
                        onExport = exportToml
                    )
                } else {
                    MainTabs(
                        palette = palette,
                        selectedTab = selectedTab,
                        onTab = { selectedTab = it },
                        configs = configs,
                        settings = settings,
                        runtime = runtime,
                        userServers = userServers,
                        officialServers = officialServers,
                        onStart = { controller.start(it) },
                        onStop = { controller.stop() },
                        onEdit = { editingConfig = it },
                        onNewConfig = {
                            editingConfig = NetworkConfig.defaultConfig(default = configs.isEmpty()).copy(
                                label = "新配置",
                                instanceName = "MoonTier-${System.currentTimeMillis().toString().takeLast(6)}"
                            )
                        },
                        onImportToml = importToml,
                        onDeleteConfig = {
                            store.deleteConfig(it.id)
                            reloadAll()
                        },
                        onDefaultConfig = {
                            store.setDefaultConfig(it.id)
                            reloadAll()
                        },
                        onExportConfig = exportToml,
                        onSaveUserServers = {
                            store.saveUserServers(it)
                            reloadAll()
                        },
                        onAddOfficialServer = { server ->
                            val current = store.loadUserServers()
                            val named = server.copy(name = nextServerName(current, server.name.ifBlank { serverNameFromAddress(server.address) }))
                            store.saveUserServers((current + named).distinctBy { it.address.lowercase() })
                            reloadAll()
                            dialog = "已添加" to server.address
                        },
                        onSettings = {
                            settings = it
                            store.saveSettings(it)
                        },
                        onSyncOfficial = {
                            scope.launch {
                                runCatching { store.syncOfficialServers() }
                                    .onSuccess {
                                        reloadAll()
                                        dialog = "同步完成" to it.message
                                    }.onFailure {
                                        dialog = "同步失败" to (it.message ?: "无法同步官方源")
                                    }
                            }
                        },
                        onDownloadUsers = { source ->
                            scope.launch {
                                runCatching { store.downloadUserServers(source) }
                                    .onSuccess {
                                        reloadAll()
                                        dialog = "下载完成" to it.message
                                    }.onFailure {
                                        dialog = "下载失败" to (it.message ?: "无法下载服务器列表")
                                    }
                            }
                        }
                    )
                }
            }

            dialog?.let { (title, body) ->
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    confirmButton = {
                        QButton(
                            text = "关闭",
                            palette = palette,
                            compact = true,
                            onClick = { dialog = null }
                        )
                    },
                    title = { Text(title, color = palette.text, fontWeight = FontWeight.SemiBold) },
                    text = { Text(body, color = palette.subText, lineHeight = 20.sp) },
                    containerColor = palette.surface,
                    shape = RoundedCornerShape(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ApplySystemBars(palette: Palette, darkMode: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = palette.background.toArgbInt()
        window.navigationBarColor = palette.background.toArgbInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (darkMode) 0 else (
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            )
    }
}

@Composable
private fun MainTabs(
    palette: Palette,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    configs: List<NetworkConfig>,
    settings: AppSettings,
    runtime: RuntimeState,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    onNewConfig: () -> Unit,
    onImportToml: () -> Unit,
    onDeleteConfig: (NetworkConfig) -> Unit,
    onDefaultConfig: (NetworkConfig) -> Unit,
    onExportConfig: (NetworkConfig) -> Unit,
    onSaveUserServers: (List<ServerEntry>) -> Unit,
    onAddOfficialServer: (ServerEntry) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onSyncOfficial: () -> Unit,
    onDownloadUsers: (String) -> Unit
) {
    val tabs = remember {
        listOf(
            NavItem("网络配置", Icons.Rounded.Wifi),
            NavItem("配置文件", Icons.Rounded.Description),
            NavItem("服务器", Icons.Rounded.Storage),
            NavItem("设置", Icons.Rounded.Settings)
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(170)) + slideInHorizontally(tween(230)) { it * dir / 8 })
                        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(190)) { -it * dir / 8 })
                },
                label = "tab"
            ) { index ->
                when (index) {
                    0 -> NetworkPage(palette, configs, runtime, onStart, onStop, onEdit)
                    1 -> ProfilesPage(palette, configs, runtime, onStart, onStop, onEdit, onNewConfig, onImportToml, onDeleteConfig, onDefaultConfig, onExportConfig)
                    2 -> ServersPage(palette, userServers, officialServers, onSaveUserServers, onAddOfficialServer, onDownloadUsers, onSyncOfficial)
                    else -> SettingsPage(palette, settings, onSettings)
                }
            }
        }
        BottomNav(palette, tabs, selectedTab, onTab)
    }
}

@Composable
private fun NetworkPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    runtime: RuntimeState,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit
) {
    val current = if (runtime.running) {
        configs.firstOrNull { it.id == runtime.runningConfigId } ?: configs.firstOrNull { it.isDefault }
    } else {
        configs.firstOrNull { it.isDefault }
    } ?: NetworkConfig.defaultConfig()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Header(
            title = if (runtime.running) "当前网络" else "默认配置",
            subtitle = current.displayName,
            palette = palette
        )
        AnimatedContent(
            targetState = runtime.running,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 18 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -it / 18 })
            },
            label = "network-mode"
        ) { running ->
            if (!running) {
                QCard(palette) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(palette.subText)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(current.networkName.ifBlank { "尚未填写网络名称" }, color = palette.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QButton(
                            text = if (runtime.starting) "启动中..." else "启动",
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            enabled = !runtime.starting,
                            icon = Icons.Rounded.PlayArrow,
                            onClick = { onStart(current) }
                        )
                        QButton(
                            text = "编辑",
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            primary = false,
                            icon = Icons.Rounded.Edit,
                            onClick = { onEdit(current) }
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QButton(
                            text = "编辑配置",
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            primary = false,
                            icon = Icons.Rounded.Edit,
                            enabled = !runtime.stopping,
                            onClick = { onEdit(current) }
                        )
                        QButton(
                            text = if (runtime.stopping) "停止中..." else "停止",
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            danger = true,
                            icon = Icons.Rounded.Stop,
                            enabled = !runtime.stopping,
                            onClick = onStop
                        )
                    }
                    RunningStatus(palette, runtime, current.displayName)
                }
            }
        }
    }
}

@Composable
private fun RunningStatus(palette: Palette, runtime: RuntimeState, label: String) {
    var logsExpanded by remember { mutableStateOf(false) }
    QCard(palette, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(runtime.statusText, color = palette.subText, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.height(14.dp))
        Text("设备列表", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (runtime.nodes.isEmpty()) {
            Text("等待节点信息", color = palette.subText, fontSize = 13.sp)
        } else {
            runtime.nodes.forEach { node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(if (node.local) palette.accent else palette.success)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(node.hostname, color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${node.ip} · ${node.role}", color = palette.subText, fontSize = 12.sp, maxLines = 1)
                    }
                    if (node.latencyMs >= 0) Text("${node.latencyMs} ms", color = palette.subText, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { logsExpanded = !logsExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (logsExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = palette.text)
        }
        AnimatedVisibility(visible = logsExpanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceVariant)
                    .padding(10.dp)
            ) {
                runtime.logs.takeLast(18).ifEmpty { listOf("暂无日志") }.forEach {
                    Text(it, color = palette.subText, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    runtime: RuntimeState,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    onNew: () -> Unit,
    onImportToml: () -> Unit,
    onDelete: (NetworkConfig) -> Unit,
    onDefault: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header("配置文件", "同一时间只能运行一个 VPN 配置", palette)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QButton("新建", palette, Modifier.weight(1f), icon = Icons.Rounded.Add, onClick = onNew)
                QButton("导入 TOML", palette, Modifier.weight(1f), primary = false, onClick = onImportToml)
            }
        }
        items(configs, key = { it.id }) { cfg ->
            val runningHere = runtime.running && runtime.runningConfigId == cfg.id
            QCard(palette) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (runningHere) palette.success else if (cfg.isDefault) palette.accent else palette.subText)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cfg.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cfg.networkName.ifBlank { "尚未填写网络名称" }, color = palette.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DefaultPill(palette, selected = cfg.isDefault, onClick = { if (!cfg.isDefault) onDefault(cfg) })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton(
                        text = if (runningHere) "停止" else "运行",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        danger = runningHere,
                        icon = if (runningHere) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = { if (runningHere) onStop() else onStart(cfg) }
                    )
                    QButton("编辑", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Edit, onClick = { onEdit(cfg) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton("导出", palette, Modifier.weight(1f), primary = false, onClick = { onExport(cfg) })
                    QButton("删除", palette, Modifier.weight(1f), primary = false, dangerText = true, icon = Icons.Rounded.Delete, enabled = !runningHere, onClick = { onDelete(cfg) })
                }
            }
        }
    }
}

@Composable
private fun ServersPage(
    palette: Palette,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onSaveUserServers: (List<ServerEntry>) -> Unit,
    onAddOfficialServer: (ServerEntry) -> Unit,
    onDownloadUsers: (String) -> Unit,
    onSyncOfficial: () -> Unit
) {
    var editable by remember(userServers) { mutableStateOf(userServers) }
    var officialOpen by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    val officialArrowRotation by animateFloatAsState(
        targetValue = if (officialOpen) 180f else 0f,
        animationSpec = tween(180),
        label = "official-arrow"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header("服务器", "用户收藏和官方源分开管理", palette) }
        item {
            QButton("新增收藏", palette, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) {
                editable = editable + ServerEntry(nextServerName(editable), "")
                onSaveUserServers(editable)
            }
        }
        item {
            QCard(palette) {
                Text("用户收藏", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                if (editable.isEmpty()) {
                    Text("暂无收藏服务器", color = palette.subText, fontSize = 13.sp)
                }
                editable.forEachIndexed { index, server ->
                    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QTextField(
                            value = server.name,
                            placeholder = "名称",
                            palette = palette,
                            onValueChange = {
                                editable = editable.replaceAt(index, server.copy(name = it))
                                onSaveUserServers(editable)
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            QTextField(
                                value = server.address,
                                placeholder = "tcp://server.example.com:11010",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                onValueChange = {
                                    editable = editable.replaceAt(index, server.copy(address = it))
                                    onSaveUserServers(editable)
                                }
                            )
                            IconButton(onClick = {
                                editable = editable.filterIndexed { i, _ -> i != index }
                                onSaveUserServers(editable)
                            }) {
                                Icon(Icons.Rounded.Delete, null, tint = palette.error)
                            }
                        }
                    }
                }
            }
        }
        item {
            QCard(palette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("官方服务器列表", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("${officialServers.size} 个服务器", color = palette.subText, fontSize = 12.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .border(BorderStroke(1.2.dp, palette.text.copy(alpha = 0.82f)), CircleShape)
                            .clickable { officialOpen = !officialOpen },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            null,
                            tint = palette.text,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = officialArrowRotation }
                        )
                    }
                }
                AnimatedVisibility(
                    visible = officialOpen,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
                ) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QButton(
                            "立即同步公共服务器",
                            palette,
                            Modifier.fillMaxWidth(),
                            primary = false,
                            icon = Icons.Rounded.Storage,
                            onClick = onSyncOfficial
                        )
                        officialServers.ifEmpty { listOf(ServerEntry("暂无官方服务器", "")) }.forEach { server ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.surfaceVariant)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(serverNameFromAddress(server.address), color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (server.address.isNotBlank()) {
                                    IconButton(onClick = { onAddOfficialServer(server) }) {
                                        Icon(Icons.Rounded.Add, null, tint = palette.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            QCard(palette) {
                Text("TXT 下载到用户收藏", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                QTextField(source, "域名或 https:// 文本地址", palette, onValueChange = { source = it })
                Spacer(Modifier.height(10.dp))
                QButton("下载到用户收藏", palette, Modifier.fillMaxWidth(), primary = false) {
                    onDownloadUsers(source)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    palette: Palette,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit
) {
    var disclaimer by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header("设置", "同步、路由和外观", palette) }
        item {
            QCard(palette) {
                SwitchRow("启动时同步公共服务器", settings.autoSyncOfficialServers, palette) {
                    onSettings(settings.copy(autoSyncOfficialServers = it))
                }
                SwitchRow("深色模式", settings.darkMode, palette) {
                    onSettings(settings.copy(darkMode = it))
                }
                SwitchRow("出口节点自动配置路由表", settings.exitNodeAutoRoutes, palette) {
                    if (it) disclaimer = true else onSettings(settings.copy(exitNodeAutoRoutes = false))
                }
            }
        }
    }

    if (disclaimer) {
        AlertDialog(
            onDismissRequest = { disclaimer = false },
            confirmButton = {
                QButton("同意并开启", palette, compact = true) {
                    onSettings(settings.copy(exitNodeAutoRoutes = true))
                    disclaimer = false
                }
            },
            dismissButton = {
                QButton("取消", palette, primary = false, compact = true) { disclaimer = false }
            },
            title = { Text("免责声明", color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "该功能会在配置了出口节点时自动把公网 IPv4 路由指向 EasyTier 虚拟网卡。禁止使用该功能违法翻越防火墙；使用该功能必须遵守所在国家或地区的法律法规。",
                    color = palette.subText,
                    lineHeight = 20.sp
                )
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun ConfigEditorScreenV2(
    palette: Palette,
    initial: NetworkConfig,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onBack: () -> Unit,
    onSave: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
) {
    var cfg by remember(initial.id) { mutableStateOf(initial) }
    var passwordVisible by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var protocolOpen by remember { mutableStateOf(false) }
    var p2pOpen by remember { mutableStateOf(false) }
    var perfOpen by remember { mutableStateOf(false) }
    var serviceOpen by remember { mutableStateOf(false) }
    var serverPicker by remember { mutableStateOf<ServerPickerState?>(null) }

    BackHandler {
        onSave(cfg)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            QCard(palette) {
                Text("编辑配置", color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                SmallLabel("配置名称", palette)
                QTextField(cfg.label, "配置名称", palette) { cfg = cfg.copy(label = it) }
                Spacer(Modifier.height(12.dp))
                ActionBar(palette) {
                    QButton("保存", palette, Modifier.weight(1f), compact = true, icon = Icons.Rounded.Check) { onSave(cfg) }
                    QButton("不保存", palette, Modifier.weight(1f), primary = false, compact = true) { onBack() }
                    QButton("导出", palette, Modifier.weight(1f), primary = false, compact = true) { onExport(cfg) }
                }
            }
        }

        item {
            QCard(palette) {
                SmallLabel("基础设置", palette)
                FieldBlock("用户名", palette) {
                    QTextField(cfg.hostname, "请输入用户名（可留空）", palette) { cfg = cfg.copy(hostname = it) }
                }
                FieldBlock("网络名称", palette) {
                    QTextField(cfg.networkName, "请输入网络名称", palette) { cfg = cfg.copy(networkName = it) }
                }
                FieldBlock("密码", palette) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        QTextField(
                            cfg.networkSecret,
                            "请输入密码",
                            palette,
                            modifier = Modifier.weight(1f),
                            password = !passwordVisible
                        ) { cfg = cfg.copy(networkSecret = it) }
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = palette.text)
                        }
                    }
                }
                FieldBlock("IP 设置", palette) {
                    SwitchRow("DHCP 自动分配", cfg.dhcp, palette) { cfg = cfg.copy(dhcp = it) }
                    Spacer(Modifier.height(8.dp))
                    QTextField(
                        cfg.ipv4,
                        "例如 10.126.126.2",
                        palette,
                        modifier = Modifier.graphicsLayer { alpha = if (cfg.dhcp) 0.42f else 1f },
                        onValueChange = { if (!cfg.dhcp) cfg = cfg.copy(ipv4 = it) }
                    )
                }
                SwitchRow("低延迟优先", cfg.latencyFirst, palette) { cfg = cfg.copy(latencyFirst = it) }
                SwitchRow("私有模式", cfg.enablePrivateMode, palette) { cfg = cfg.copy(enablePrivateMode = it) }
            }
        }

        item {
            ExpandableQCard("服务器", serverOpen, { serverOpen = it }, palette) {
                ListEditor(
                    title = "服务器列表",
                    values = cfg.peerUrls,
                    placeholder = "tcp://server.example.com:11010",
                    palette = palette
                ) {
                    cfg = cfg.copy(peerUrls = it)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QButton("从收藏", palette, Modifier.weight(1f), primary = false, enabled = userServers.any { it.address.isNotBlank() }) {
                        serverPicker = ServerPickerState("用户收藏", userServers)
                    }
                    QButton("从官方", palette, Modifier.weight(1f), primary = false, enabled = officialServers.any { it.address.isNotBlank() }) {
                        serverPicker = ServerPickerState("官方服务器", officialServers)
                    }
                }
            }
        }

        item {
            ExpandableQCard("传输协议", protocolOpen, { protocolOpen = it }, palette) {
                SwitchRow("启用 KCP 代理", cfg.enableKcpProxy, palette) { cfg = cfg.copy(enableKcpProxy = it) }
                SwitchRow("禁用 KCP 输入", cfg.disableKcpInput, palette) { cfg = cfg.copy(disableKcpInput = it) }
                SwitchRow("启用 QUIC 代理", cfg.enableQuicProxy, palette) { cfg = cfg.copy(enableQuicProxy = it) }
                SwitchRow("禁用 QUIC 输入", cfg.disableQuicInput, palette) { cfg = cfg.copy(disableQuicInput = it) }
                SwitchRow("禁止转发 KCP", cfg.disableRelayKcp, palette) { cfg = cfg.copy(disableRelayKcp = it) }
                SwitchRow("禁止转发 QUIC", cfg.disableRelayQuic, palette) { cfg = cfg.copy(disableRelayQuic = it) }
                SwitchRow("允许转发其他网络 KCP", cfg.enableRelayForeignNetworkKcp, palette) { cfg = cfg.copy(enableRelayForeignNetworkKcp = it) }
                SwitchRow("允许转发其他网络 QUIC", cfg.enableRelayForeignNetworkQuic, palette) { cfg = cfg.copy(enableRelayForeignNetworkQuic = it) }
                SwitchRow("启用加密", cfg.enableEncryption, palette) { cfg = cfg.copy(enableEncryption = it) }
                FieldBlock("默认连接协议", palette) {
                    QSelect(cfg.defaultProtocol, listOf("tcp", "udp", "ws", "wss", "wg", "quic"), palette) { cfg = cfg.copy(defaultProtocol = it) }
                }
                FieldBlock("默认加密协议", palette) {
                    QSelect(cfg.encryptionAlgorithm, listOf("aes-gcm", "chacha20-poly1305", "none"), palette) { cfg = cfg.copy(encryptionAlgorithm = it) }
                }
            }
        }

        item {
            ExpandableQCard("P2P 连接", p2pOpen, { p2pOpen = it }, palette) {
                SwitchRow("仅 P2P", cfg.p2pOnly, palette) { cfg = cfg.copy(p2pOnly = it) }
                SwitchRow("禁用 P2P", cfg.disableP2p, palette) { cfg = cfg.copy(disableP2p = it) }
                SwitchRow("需要 P2P", cfg.needP2p, palette) { cfg = cfg.copy(needP2p = it) }
                SwitchRow("按需 P2P", cfg.lazyP2p, palette) { cfg = cfg.copy(lazyP2p = it) }
                SwitchRow("禁用 UDP 打洞", cfg.disableUdpHolePunching, palette) { cfg = cfg.copy(disableUdpHolePunching = it) }
                SwitchRow("禁用 TCP 打洞", cfg.disableTcpHolePunching, palette) { cfg = cfg.copy(disableTcpHolePunching = it) }
                SwitchRow("禁用 UPnP", cfg.disableUpnp, palette) { cfg = cfg.copy(disableUpnp = it) }
                SwitchRow("禁用对称 NAT 打洞", cfg.disableSymHolePunching, palette) { cfg = cfg.copy(disableSymHolePunching = it) }
                SwitchRow("转发 RPC 包", cfg.relayAllPeerRpc, palette) { cfg = cfg.copy(relayAllPeerRpc = it) }
                SwitchRow("仅使用物理网卡", cfg.bindDevice, palette) { cfg = cfg.copy(bindDevice = it) }
            }
        }

        item {
            ExpandableQCard("性能与系统", perfOpen, { perfOpen = it }, palette) {
                SwitchRow("启用多线程", cfg.multiThread, palette) { cfg = cfg.copy(multiThread = it) }
                SwitchRow("使用用户态协议栈", cfg.useSmoltcp, palette) { cfg = cfg.copy(useSmoltcp = it) }
                SwitchRow("无 TUN 模式", cfg.noTun, palette) { cfg = cfg.copy(noTun = it) }
                SwitchRow("禁用 IPv6", cfg.disableIpv6, palette) { cfg = cfg.copy(disableIpv6 = it) }
                FieldBlock("MTU 值", palette) {
                    QTextField(if (cfg.mtu > 0) cfg.mtu.toString() else "", "最大 1380，留空使用默认", palette) {
                        val mtu = it.trim().toIntOrNull()?.coerceIn(0, 1380) ?: 0
                        cfg = cfg.copy(mtu = mtu)
                    }
                }
            }
        }

        item {
            ExpandableQCard("网络服务", serviceOpen, { serviceOpen = it }, palette) {
                SwitchRow("启用出口节点", cfg.enableExitNode, palette) { cfg = cfg.copy(enableExitNode = it) }
                SwitchRow("系统转发", cfg.proxyForwardBySystem, palette) { cfg = cfg.copy(proxyForwardBySystem = it) }
                SwitchRow("启用魔法 DNS", cfg.enableMagicDns, palette) { cfg = cfg.copy(enableMagicDns = it) }
                SwitchRow("启用网络白名单", cfg.enableRelayNetworkWhitelist, palette) { cfg = cfg.copy(enableRelayNetworkWhitelist = it) }
                AnimatedVisibility(cfg.enableRelayNetworkWhitelist, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                    Column(Modifier.padding(top = 8.dp)) {
                        ListEditor("网络白名单", cfg.relayNetworkWhitelist, "网络名称", palette) {
                            cfg = cfg.copy(relayNetworkWhitelist = it)
                        }
                    }
                }
                ListEditor("监听地址", cfg.listenerUrls, "tcp://0.0.0.0:11010", palette) {
                    cfg = cfg.copy(listenerUrls = it)
                }
                ListEditor("子网代理 CIDR", cfg.proxyCidrs, "192.168.1.0/24", palette) {
                    cfg = cfg.copy(proxyCidrs = it)
                }
                Text("自定义进入 VPN 的路由，将禁用子网代理等自动传播的路由", color = palette.subText, fontSize = 12.sp)
                ListEditor("自定义路由规则", cfg.routes, "10.0.0.0/8", palette) {
                    cfg = cfg.copy(routes = it, enableManualRoutes = it.isNotEmpty())
                }
                ListEditor("出口节点列表", cfg.exitNodes, "10.126.126.1", palette) {
                    cfg = cfg.copy(exitNodes = it)
                }
            }
        }
    }

    serverPicker?.let { picker ->
        ServerPickerDialog(
            palette = palette,
            state = picker,
            currentAddresses = cfg.peerUrls,
            onDismiss = { serverPicker = null },
            onConfirm = { selected ->
                cfg = cfg.copy(peerUrls = (cfg.peerUrls + selected.map { it.address }).cleanItems())
                serverPicker = null
            }
        )
    }
}

@Composable
private fun ConfigEditorScreen(
    palette: Palette,
    initial: NetworkConfig,
    userServers: List<ServerEntry>,
    onBack: () -> Unit,
    onSave: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
) {
    var cfg by remember(initial.id) { mutableStateOf(initial) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.Close, null, tint = palette.text)
                }
                Header("编辑配置", cfg.displayName, palette, modifier = Modifier.weight(1f))
            }
        }
        item {
            QCard(palette) {
                FormLabel("基础")
                QTextField(cfg.label, "配置名称", palette) { cfg = cfg.copy(label = it) }
                Spacer(Modifier.height(8.dp))
                QTextField(cfg.instanceName, "实例名称", palette) { cfg = cfg.copy(instanceName = it) }
                Spacer(Modifier.height(8.dp))
                QTextField(cfg.networkName, "网络名称", palette) { cfg = cfg.copy(networkName = it) }
                Spacer(Modifier.height(8.dp))
                QTextField(cfg.networkSecret, "网络密码", palette, password = true) { cfg = cfg.copy(networkSecret = it) }
                Spacer(Modifier.height(8.dp))
                QTextField(cfg.hostname, "主机名", palette) { cfg = cfg.copy(hostname = it) }
                Spacer(Modifier.height(8.dp))
                SwitchRow("DHCP 自动分配虚拟 IP", cfg.dhcp, palette) { cfg = cfg.copy(dhcp = it) }
                AnimatedVisibility(!cfg.dhcp) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        QTextField(cfg.ipv4, "虚拟 IPv4，例如 10.126.126.2", palette) { cfg = cfg.copy(ipv4 = it) }
                    }
                }
            }
        }
        item {
            QCard(palette) {
                ListEditor(
                    title = "服务器",
                    values = cfg.peerUrls,
                    placeholder = "tcp://server.example.com:11010",
                    palette = palette,
                    quickAdd = userServers.map { it.address },
                    onValues = { cfg = cfg.copy(peerUrls = it) }
                )
            }
        }
        item {
            QCard(palette) {
                ListEditor("监听地址", cfg.listenerUrls, "tcp://0.0.0.0:11010", palette) {
                    cfg = cfg.copy(listenerUrls = it)
                }
            }
        }
        item {
            QCard(palette) {
                ListEditor("代理子网", cfg.proxyCidrs, "192.168.1.0/24", palette) {
                    cfg = cfg.copy(proxyCidrs = it)
                }
            }
        }
        item {
            QCard(palette) {
                SwitchRow("启用手动路由", cfg.enableManualRoutes, palette) { cfg = cfg.copy(enableManualRoutes = it) }
                AnimatedVisibility(cfg.enableManualRoutes) {
                    Column(Modifier.padding(top = 10.dp)) {
                        ListEditor("手动路由", cfg.routes, "10.0.0.0/8", palette) {
                            cfg = cfg.copy(routes = it)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ListEditor("出口节点列表", cfg.exitNodes, "10.126.126.1", palette) {
                    cfg = cfg.copy(exitNodes = it)
                }
            }
        }
        item {
            QCard(palette) {
                FormLabel("功能")
                SwitchRow("允许本机成为出口节点", cfg.enableExitNode, palette) { cfg = cfg.copy(enableExitNode = it) }
                SwitchRow("启用 Magic DNS", cfg.enableMagicDns, palette) { cfg = cfg.copy(enableMagicDns = it) }
                SwitchRow("私有网络模式", cfg.enablePrivateMode, palette) { cfg = cfg.copy(enablePrivateMode = it) }
                SwitchRow("启用加密", cfg.enableEncryption, palette) { cfg = cfg.copy(enableEncryption = it) }
                SwitchRow("启用 KCP 代理", cfg.enableKcpProxy, palette) { cfg = cfg.copy(enableKcpProxy = it) }
                SwitchRow("延迟优先", cfg.latencyFirst, palette) { cfg = cfg.copy(latencyFirst = it) }
                SwitchRow("无 TUN 模式", cfg.noTun, palette) { cfg = cfg.copy(noTun = it) }
            }
        }
        item {
            QCard(palette) {
                FormLabel("转发和白名单")
                SwitchRow("系统转发代理子网", cfg.proxyForwardBySystem, palette) { cfg = cfg.copy(proxyForwardBySystem = it) }
                SwitchRow("启用中继网络白名单", cfg.enableRelayNetworkWhitelist, palette) {
                    cfg = cfg.copy(enableRelayNetworkWhitelist = it)
                }
                AnimatedVisibility(cfg.enableRelayNetworkWhitelist) {
                    Column(Modifier.padding(top = 10.dp)) {
                        ListEditor("中继网络白名单", cfg.relayNetworkWhitelist, "network-name", palette) {
                            cfg = cfg.copy(relayNetworkWhitelist = it)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QButton("保存", palette, Modifier.weight(1f), icon = Icons.Rounded.Check) { onSave(cfg) }
                QButton("不保存", palette, Modifier.weight(1f), primary = false) { onBack() }
            }
            Spacer(Modifier.height(8.dp))
            QButton("导出", palette, Modifier.fillMaxWidth(), primary = false) { onExport(cfg) }
        }
    }
}

@Composable
private fun ExpandableQCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    palette: Palette,
    content: @Composable ColumnScope.() -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "expand-arrow"
    )
    QCard(palette) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.2.dp, palette.text.copy(alpha = 0.82f)), CircleShape)
                    .clickable { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    null,
                    tint = palette.text,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(170)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ActionBar(palette: Palette, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(0.8.dp, palette.border), RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun SmallLabel(text: String, palette: Palette) {
    Text(
        text,
        color = palette.text.copy(alpha = 0.58f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun FieldBlock(label: String, palette: Palette, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = palette.text, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun QSelect(
    value: String,
    options: List<String>,
    palette: Palette,
    onValue: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { options.firstOrNull().orEmpty() }, color = palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = palette.subText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValue(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomNav(palette: Palette, items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .navigationBarsPadding()
            .background(palette.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(palette.border.copy(alpha = 0.7f))
                .align(Alignment.TopCenter)
        )
        val slot = maxWidth / items.size
        val pillX by animateDpAsState(
            targetValue = slot * selected + (slot - 64.dp) / 2,
            animationSpec = tween(220),
            label = "nav-pill"
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .graphicsLayer { translationX = pillX.toPx() }
                .size(width = 64.dp, height = 34.dp)
                .clip(CircleShape)
                .background(palette.accent)
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val active = selected == index
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(110), label = "nav-scale")
                Column(
                    modifier = Modifier
                        .width(slot)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable(source, indication = null) { onSelect(index) }
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(item.icon, null, tint = if (active) palette.onAccent else palette.subText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        item.label,
                        color = if (active) palette.text else palette.subText,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, palette: Palette, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = palette.subText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QCard(palette: Palette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface)
            .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(8.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun QButton(
    text: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    dangerText: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.982f else 1f, tween(95), label = "button-scale")
    val bg = when {
        !enabled -> palette.text.copy(alpha = 0.06f)
        danger -> palette.error
        primary -> palette.accent
        else -> palette.secondaryButton
    }
    val fg = when {
        dangerText -> palette.error
        primary || danger -> palette.onAccent
        else -> palette.text
    }
    Box(
        modifier = modifier
            .height(if (compact) 38.dp else 46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (dangerText) Modifier.border(BorderStroke(0.8.dp, palette.error), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(source, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val overlayAlpha by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 42 else 125), label = "button-overlay")
        Box(
            Modifier
                .fillMaxSize()
                .background(if (primary || danger) Color.White.copy(alpha = 0.15f * overlayAlpha) else palette.overlay.copy(alpha = overlayAlpha))
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) fg else palette.subText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) fg else palette.subText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun QTextField(
    value: String,
    placeholder: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (singleLine) 44.dp else 88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(if (focused) 1.2.dp else 0.7.dp, if (focused) palette.accent.copy(alpha = 0.82f) else palette.border), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        val hintAlpha by animateFloatAsState(if (value.isEmpty() && !focused) 1f else 0f, tween(140), label = "hint")
        if (hintAlpha > 0.01f) {
            Text(
                placeholder,
                color = palette.subText.copy(alpha = 0.8f * hintAlpha),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, palette: Palette, onChecked: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 0.dp, tween(180), label = "switch-thumb")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(130), label = "switch-scale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (checked) palette.accent else palette.secondaryButton)
                .border(BorderStroke(if (checked) 0.dp else 0.7.dp, palette.border), CircleShape)
                .clickable(source, indication = null) { onChecked(!checked) },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .graphicsLayer { translationX = thumbOffset.toPx() }
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(palette.onAccent)
                    .border(BorderStroke(0.7.dp, palette.border), CircleShape)
            )
        }
    }
}

@Composable
private fun ServerPickerDialog(
    palette: Palette,
    state: ServerPickerState,
    currentAddresses: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<ServerEntry>) -> Unit
) {
    val servers = remember(state) {
        state.servers
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address.lowercase() }
    }
    val existing = remember(currentAddresses) { currentAddresses.map { it.lowercase() }.toHashSet() }
    var selected by remember(state) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(state.title, color = palette.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(180))
            ) {
                if (servers.isEmpty()) {
                    Text("暂无可选服务器", color = palette.subText, fontSize = 14.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(servers, key = { it.address }) { server ->
                            val addressKey = server.address.lowercase()
                            val checked = addressKey in selected
                            val alreadyAdded = addressKey in existing
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceVariant)
                                    .clickable(enabled = !alreadyAdded) {
                                        selected = if (checked) selected - addressKey else selected + addressKey
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked || alreadyAdded,
                                    enabled = !alreadyAdded,
                                    onCheckedChange = {
                                        selected = if (it) selected + addressKey else selected - addressKey
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        serverNameFromAddress(server.address),
                                        color = if (alreadyAdded) palette.subText else palette.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (alreadyAdded) {
                                        Text("已在当前配置中", color = palette.subText, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            QButton(
                "添加选中",
                palette,
                Modifier.width(112.dp),
                compact = true,
                enabled = selected.isNotEmpty()
            ) {
                onConfirm(servers.filter { it.address.lowercase() in selected })
            }
        },
        dismissButton = {
            QButton("取消", palette, Modifier.width(82.dp), primary = false, compact = true, onClick = onDismiss)
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ListEditor(
    title: String,
    values: List<String>,
    placeholder: String,
    palette: Palette,
    quickAdd: List<String> = emptyList(),
    quickAddLabel: String? = null,
    secondaryQuickAdd: List<String> = emptyList(),
    secondaryQuickAddLabel: String? = null,
    onValues: (List<String>) -> Unit
) {
    var local by remember(values) { mutableStateOf(values.ifEmpty { emptyList() }) }
    val quickActions = buildList {
        if (quickAddLabel != null || quickAdd.isNotEmpty()) add((quickAddLabel ?: "收藏") to quickAdd)
        if (secondaryQuickAddLabel != null || secondaryQuickAdd.isNotEmpty()) add((secondaryQuickAddLabel ?: "官方") to secondaryQuickAdd)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
    ) {
        FormLabel(title)
        local.forEachIndexed { index, value ->
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QTextField(
                    value = value,
                    placeholder = placeholder,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onValueChange = {
                        local = local.replaceAt(index, it)
                        onValues(local.cleanItems())
                    }
                )
                IconButton(onClick = {
                    local = local.filterIndexed { i, _ -> i != index }
                    onValues(local.cleanItems())
                }) {
                    Icon(Icons.Rounded.Delete, null, tint = palette.error)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QButton("添加", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Add) {
                local = local + ""
                onValues(local.cleanItems())
            }
            quickActions.forEach { (label, items) ->
                QButton(label, palette, Modifier.weight(1f), primary = false, enabled = items.isNotEmpty()) {
                    local = (local + items).cleanItems()
                    onValues(local)
                }
            }
        }
    }
}

@Composable
private fun DefaultPill(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .border(BorderStroke(0.8.dp, if (selected) palette.accent.copy(alpha = 0.26f) else palette.border), CircleShape)
            .background(if (selected) palette.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(enabled = !selected) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("默认", color = if (selected) palette.accent else palette.subText, fontSize = 11.sp)
    }
}

@Composable
private fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun FormLabel(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, old -> if (i == index) value else old }

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
