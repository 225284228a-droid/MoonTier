package cn.moonflow.easytier

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var controller: EasyTierController
    private lateinit var rootController: RootTierController
    private var pendingVpnConfig by mutableStateOf<String?>(null)
    private var pendingExportText: String = ""
    private var pendingDiagnosticText: String = ""
    private var onImportedConfig: ((NetworkConfig) -> Unit)? = null
    private var onFileMessage: ((String, String) -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val vpnConfig = pendingVpnConfig
        if (result.resultCode == Activity.RESULT_OK) {
            AppDiagnostics.event("vpn", "VPN permission granted")
            controller.onVpnPermissionGranted()
        } else if (vpnConfig != null) {
            AppDiagnostics.warn("vpn", "VPN permission denied")
            controller.onVpnPermissionDenied(vpnConfig)
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

    private val importCoreZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AppDiagnostics.event("root", "core ZIP selected for import")
        rootController.installCoreZip(uri)
    }

    private val exportDiagnosticsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingDiagnosticText) }
                ?: error("无法写入日志文件")
        }.onSuccess {
            AppDiagnostics.info("launcher", "diagnostic report exported")
            onFileMessage?.invoke("导出完成", "应用日志已写入文件")
        }.onFailure {
            AppDiagnostics.error("launcher", "diagnostic report export failed", it)
            onFileMessage?.invoke("导出失败", it.message ?: "无法写入日志文件")
        }
    }

    private var isForeground by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingVpnConfig = savedInstanceState?.getString("pending_vpn_permission")
        store = ConfigStore(applicationContext)
        AppDiagnostics.initialize(applicationContext, store.loadSettings().coreLogLevel)
        installUncaughtExceptionHandler()
        AppDiagnostics.event("launcher", "MainActivity created")
        controller = EasyTierController.get(applicationContext)
        rootController = RootTierController(applicationContext)

        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        setContent {
            LaunchedEffect(isForeground, controller.pendingVpnConfig, pendingVpnConfig) {
                if (isForeground && pendingVpnConfig == null) controller.pendingVpnConfig?.let(::requestVpn)
            }
            MoonTierApp(
                isForeground = isForeground,
                store = store,
                controller = controller,
                rootController = rootController,
                importToml = { importTomlLauncher.launch(arrayOf("text/*", "application/toml", "application/octet-stream")) },
                importCoreZip = { importCoreZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                exportToml = { config ->
                    pendingExportText = TomlCodec.build(config)
                    exportTomlLauncher.launch("${config.displayName}.toml")
                },
                exportDiagnostics = { report ->
                    pendingDiagnosticText = report
                    exportDiagnosticsLauncher.launch("moontier-diagnostics.txt")
                },
                bindImportHandler = { imported, message ->
                    onImportedConfig = imported
                    onFileMessage = message
                }
            )
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
                controller.updatePollingConditions(true)
                rootController.updatePollingConditions(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                controller.updatePollingConditions(false)
                rootController.updatePollingConditions(false)
            }
        })
    }

    override fun onDestroy() {
        AppDiagnostics.event("launcher", "MainActivity destroyed")
        rootController.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_vpn_permission", pendingVpnConfig)
        super.onSaveInstanceState(outState)
    }

    private fun requestVpn(vpnJson: String) {
        AppDiagnostics.event("vpn", "VPN permission check requested")
        val intent = runCatching { VpnService.prepare(this) }.getOrElse { error ->
            controller.onVpnServiceStartFailed(error)
            return
        }
        if (intent != null) {
            pendingVpnConfig = vpnJson
            runCatching { vpnPermissionLauncher.launch(intent) }
                .onFailure { controller.onVpnServiceStartFailed(it) }
        } else {
            controller.onVpnPermissionGranted()
        }
    }

    private fun installUncaughtExceptionHandler() {
        synchronized(MainActivity::class.java) {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                AppDiagnostics.error("launcher", "uncaught crash on ${thread.name}", error)
                previous?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
        }
    }

    private companion object {
        @Volatile
        var crashHandlerInstalled = false
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private data class ServerPickerState(
    val title: String,
    val servers: List<ServerEntry>
)

private fun isFullConfigServerUrl(value: String): Boolean =
    value.trim().contains("://")

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
    isForeground: Boolean,
    store: ConfigStore,
    controller: EasyTierController,
    rootController: RootTierController,
    importToml: () -> Unit,
    importCoreZip: () -> Unit,
    exportToml: (NetworkConfig) -> Unit,
    exportDiagnostics: (String) -> Unit,
    bindImportHandler: (((NetworkConfig) -> Unit, (String, String) -> Unit) -> Unit)
) {
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var nodesExpanded by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf(store.loadConfigs()) }
    var userServers by remember { mutableStateOf(store.loadUserServers()) }
    var officialServers by remember { mutableStateOf(store.loadOfficialServers()) }
    var officialSyncJob by remember { mutableStateOf<Job?>(null) }
    var officialSyncInProgress by remember { mutableStateOf(false) }
    var officialSyncMessage by remember { mutableStateOf<String?>(null) }
    var officialSyncFailed by remember { mutableStateOf(false) }
    var modeSwitching by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rootAvailable by remember { mutableStateOf(false) }
    var rootChecking by remember { mutableStateOf(false) }
    var rootSelectedConfigId by remember {
        mutableStateOf(configs.firstOrNull { it.isDefault }?.id ?: configs.firstOrNull()?.id.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val palette = if (settings.darkMode) Palette.Dark else Palette.Light
    val runtime = controller.state

    fun reloadAll() {
        configs = store.loadConfigs()
        if (configs.none { it.id == rootSelectedConfigId }) {
            rootSelectedConfigId = configs.firstOrNull { it.isDefault }?.id ?: configs.firstOrNull()?.id.orEmpty()
        }
        userServers = store.loadUserServers()
        officialServers = store.loadOfficialServers()
        settings = store.loadSettings()
    }

    fun saveSettings(next: AppSettings) {
        if (modeSwitching) return
        val logLevelChanged = CoreLogLevel.normalize(next.coreLogLevel) != CoreLogLevel.normalize(settings.coreLogLevel)
        if (next.rootModeEnabled != settings.rootModeEnabled) {
            modeSwitching = true
            scope.launch {
                try {
                    if (next.rootModeEnabled) controller.stopAllAndWait() else rootController.stopAllAndWait()
                    val updated = store.loadSettings().copy(rootModeEnabled = next.rootModeEnabled)
                    store.saveSettings(updated)
                    settings = updated
                    if (!updated.rootModeEnabled) controller.attachIfRunning(configs)
                } catch (error: Exception) {
                    dialog = "模式切换失败" to (error.message ?: "原网络尚未停止")
                } finally {
                    modeSwitching = false
                }
            }
            return
        }
        if (next.coreReleaseChannel != settings.coreReleaseChannel) rootController.coreChannelChanged()
        val updated = next.copy(ffiConfigServerUrl = store.loadSettings().ffiConfigServerUrl)
        settings = updated
        store.saveSettings(updated)
        AppDiagnostics.configure(next.coreLogLevel)
        if (logLevelChanged) {
            controller.updateLogLevel(next.coreLogLevel)
            rootController.updateLogLevel(next.coreLogLevel)
        }
        AppDiagnostics.info("launcher", "settings saved mode=${if (next.rootModeEnabled) "root" else "vpn"} log=${next.coreLogLevel}")
    }

    fun refreshRootStatus() {
        scope.launch {
            rootChecking = true
            try {
                rootAvailable = withContext(Dispatchers.IO) {
                    RootManager.probe(refresh = true).available
                }
            } finally {
                rootChecking = false
            }
        }
    }

    fun syncOfficialServers() {
        if (officialSyncInProgress || officialSyncJob?.isActive == true) return
        officialSyncInProgress = true
        officialSyncMessage = null
        officialSyncJob = scope.launch {
            try {
                runCatching {
                    val result = store.syncOfficialServers()
                    result to store.loadOfficialServers()
                }.onSuccess { (result, servers) ->
                    officialServers = servers
                    officialSyncFailed = false
                    officialSyncMessage = "已同步 ${result.count} 个服务器"
                }.onFailure { error ->
                    officialSyncFailed = true
                    officialSyncMessage = error.message ?: "无法同步官方源"
                }
            } finally {
                officialSyncInProgress = false
                officialSyncJob = null
            }
        }
    }

    LaunchedEffect(isForeground, nodesExpanded) {
        controller.updatePollingConditions(isForeground, nodesExpanded)
        rootController.updatePollingConditions(isForeground)
    }

    LaunchedEffect(controller.configServerState.serverUrl) {
        settings = settings.copy(ffiConfigServerUrl = store.loadSettings().ffiConfigServerUrl)
    }

    LaunchedEffect(Unit) {
        bindImportHandler(
            { cfg ->
                store.saveConfig(cfg.copy(id = java.util.UUID.randomUUID().toString(), isDefault = configs.isEmpty()))
                reloadAll()
                dialog = "导入完成" to "TOML 配置已加入配置文件列表"
            },
            { title, body -> dialog = title to body }
        )
    }

    LaunchedEffect(Unit) {
        controller.attachIfRunning(configs)
        if (settings.autoSyncOfficialServers) {
            syncOfficialServers()
        }
    }

    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) {
            RootManager.probe().available
        }
    }

    LaunchedEffect(isForeground, settings.rootModeEnabled, settings.coreAutoUpdate, settings.coreReleaseChannel) {
        if (isForeground && settings.rootModeEnabled && settings.coreAutoUpdate) rootController.checkCoreUpdate()
    }

    LaunchedEffect(
        settings.rootModeEnabled,
        settings.configServerAutoConnect,
        settings.configServerUrl,
        rootAvailable,
        rootController.state.core.ready
    ) {
        if (
            settings.rootModeEnabled &&
            settings.configServerAutoConnect &&
            isFullConfigServerUrl(settings.configServerUrl) &&
            rootAvailable &&
            rootController.state.core.ready &&
            !rootController.state.configServer.running &&
            !rootController.state.configServer.starting
        ) {
            rootController.startConfigServer(settings)
        }
    }

    ApplySystemBars(palette, settings.darkMode)

    MaterialTheme(
        colorScheme = (if (settings.darkMode) darkColorScheme() else lightColorScheme()).copy(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.secondaryButton,
            onSecondary = palette.text,
            surface = palette.surface,
            background = palette.background,
            onSurface = palette.text,
            outline = palette.border,
            error = palette.error
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            AnimatedContent(
                targetState = editingConfig,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(tween(220)) + scaleOut(targetScale = 0.94f, animationSpec = tween(220)))
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
                            if (it.id == editing.id) {
                                store.saveConfig(it)
                            } else {
                                val current = store.loadConfigs().filterNot { cfg -> cfg.id == editing.id }
                                store.saveConfigs(current + it)
                            }
                            reloadAll()
                            editingConfig = null
                        },
                        onExport = exportToml
                    )
                } else {
                    MainTabs(
                        palette = palette,
                        nodesExpanded = nodesExpanded,
                        onNodesExpandedChange = { nodesExpanded = it },
                        selectedTab = selectedTab,
                        onTab = { selectedTab = it },
                        configs = configs,
                        settings = settings,
                        runtime = runtime,
                        ffiInstances = controller.instances,
                        ffiConfigServer = controller.configServerState,
                        vpnPermissionRequired = controller.vpnPermissionRequired,
                        rootController = rootController,
                        rootAvailable = rootAvailable,
                        rootChecking = rootChecking,
                        rootMode = settings.rootModeEnabled,
                        rootSelectedConfigId = rootSelectedConfigId,
                        onRootConfigSelected = { rootSelectedConfigId = it },
                        onRefreshRoot = ::refreshRootStatus,
                        userServers = userServers,
                        officialServers = officialServers,
                        onStart = { controller.start(it) },
                        onRequestVpnAuthorization = { controller.requestVpnAuthorization() },
                        onSetFfiInstanceEnabled = { id, enabled -> controller.setInstanceEnabled(id, enabled) },
                        onEdit = { editingConfig = it },
                        onNewConfig = {
                            editingConfig = NetworkConfig.defaultConfig(default = configs.isEmpty()).copy(
                                label = "新配置",
                                instanceName = "MoonTier-${System.currentTimeMillis().toString().takeLast(6)}"
                            )
                        },
                        onImportToml = importToml,
                        onDeleteConfig = {
                            if (settings.rootModeEnabled) rootController.stop(it.id)
                            store.deleteConfig(it.id)
                            if (!settings.rootModeEnabled) controller.forgetConfig(it.id)
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
                            saveSettings(it)
                        },
                        onSyncOfficial = ::syncOfficialServers,
                        officialSyncInProgress = officialSyncInProgress,
                        officialSyncMessage = officialSyncMessage,
                        officialSyncFailed = officialSyncFailed,
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
                        },
                        onConnectConfigServer = {
                            if (!isFullConfigServerUrl(settings.configServerUrl)) {
                                dialog = "配置服务器" to "请填写完整配置服务器 URL，例如 udp://host:22020/admin"
                            } else {
                                rootController.startConfigServer(settings)
                            }
                        },
                        onDisconnectConfigServer = { rootController.stopConfigServer() },
                        onSaveFfiConfigServer = { url ->
                            controller.configureConfigServer(url)
                        },
                        onImportCoreZip = importCoreZip,
                        onExportDiagnostics = {
                            exportDiagnostics(
                                AppDiagnostics.buildReport(
                                    settings = settings,
                                    runtime = runtime,
                                    root = rootController.state,
                                    managerLog = rootController.diagnosticLogTail()
                                )
                            )
                        }
                    )
                }
            }

            if (modeSwitching) {
                Box(
                    Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.85f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center
                ) {
                    Text("正在切换模式", color = palette.text, fontSize = 16.sp)
                }
            }
            dialog?.let { (title, body) ->
                AppleToast(
                    title = title,
                    body = body,
                    palette = palette,
                    onDismiss = { dialog = null }
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
        @Suppress("DEPRECATION")
        // Keep explicit bar colors until the app is migrated to a full edge-to-edge layout.
        window.statusBarColor = palette.background.toArgbInt()
        @Suppress("DEPRECATION")
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
    nodesExpanded: Boolean,
    onNodesExpandedChange: (Boolean) -> Unit,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    configs: List<NetworkConfig>,
    settings: AppSettings,
    runtime: RuntimeState,
    ffiInstances: List<FfiInstanceState>,
    ffiConfigServer: RootConfigServerState,
    vpnPermissionRequired: Boolean,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootChecking: Boolean,
    rootMode: Boolean,
    rootSelectedConfigId: String,
    onRootConfigSelected: (String) -> Unit,
    onRefreshRoot: () -> Unit,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onStart: (NetworkConfig) -> Unit,
    onRequestVpnAuthorization: () -> Unit,
    onSetFfiInstanceEnabled: (String, Boolean) -> Unit,
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
    officialSyncInProgress: Boolean,
    officialSyncMessage: String?,
    officialSyncFailed: Boolean,
    onDownloadUsers: (String) -> Unit,
    onConnectConfigServer: () -> Unit,
    onDisconnectConfigServer: () -> Unit,
    onSaveFfiConfigServer: (String) -> Unit,
    onImportCoreZip: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val tabs = remember {
        listOf(
            NavItem("运行状态", Icons.Rounded.Wifi),
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
                .pointerInput(selectedTab) {
                    var offsetX = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -150f && selectedTab < tabs.size - 1) {
                                onTab(selectedTab + 1)
                            } else if (offsetX > 150f && selectedTab > 0) {
                                onTab(selectedTab - 1)
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, amount -> offsetX += amount }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(tween(220)) + scaleOut(targetScale = 0.94f, animationSpec = tween(220)))
                },
                label = "tab"
            ) { index ->
                when (index) {
                    0 -> NetworkPage(palette, configs, runtime, ffiInstances, ffiConfigServer, vpnPermissionRequired, nodesExpanded, onNodesExpandedChange, onStart, onRequestVpnAuthorization, onSetFfiInstanceEnabled, onEdit, rootController, rootAvailable, rootMode, rootSelectedConfigId, onRootConfigSelected)
                    1 -> ProfilesPage(palette, configs, ffiInstances, rootController, rootAvailable, rootMode, onStart, onSetFfiInstanceEnabled, onEdit, onNewConfig, onImportToml, onDeleteConfig, onDefaultConfig, onExportConfig)
                    2 -> ServersPage(
                        palette = palette,
                        userServers = userServers,
                        officialServers = officialServers,
                        rootMode = rootMode,
                        rootAvailable = rootAvailable,
                        settings = settings,
                        configServer = if (rootMode) rootController.state.configServer else ffiConfigServer,
                        onSettings = onSettings,
                        onConnectConfigServer = onConnectConfigServer,
                        onDisconnectConfigServer = onDisconnectConfigServer,
                        onSaveFfiConfigServer = onSaveFfiConfigServer,
                        onSaveUserServers = onSaveUserServers,
                        onAddOfficialServer = onAddOfficialServer,
                        onDownloadUsers = onDownloadUsers,
                        onSyncOfficial = onSyncOfficial,
                        officialSyncInProgress = officialSyncInProgress,
                        officialSyncMessage = officialSyncMessage,
                        officialSyncFailed = officialSyncFailed
                    )
                    else -> SettingsPage(
                        palette = palette,
                        settings = settings,
                        rootController = rootController,
                        rootAvailable = rootAvailable,
                        rootChecking = rootChecking,
                        onSettings = onSettings,
                        onRefreshRoot = onRefreshRoot,
                        onImportCoreZip = onImportCoreZip,
                        onExportDiagnostics = onExportDiagnostics
                    )
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
    ffiInstances: List<FfiInstanceState>,
    ffiConfigServer: RootConfigServerState,
    vpnPermissionRequired: Boolean,
    nodesExpanded: Boolean,
    onNodesExpandedChange: (Boolean) -> Unit,
    onStart: (NetworkConfig) -> Unit,
    onRequestVpnAuthorization: () -> Unit,
    onSetFfiInstanceEnabled: (String, Boolean) -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootMode: Boolean,
    rootSelectedConfigId: String,
    onRootConfigSelected: (String) -> Unit
) {
    val rootState = rootController.state
    val sessions = if (rootMode) {
        rootState.instances.map {
            NetworkSession(it.configId, it.displayName, it.running || it.starting || it.stopping, it.running, it.localCidr,
                it.nodes, it.logs, it.error, noTun = configs.firstOrNull { cfg -> cfg.id == it.configId }?.noTun == true,
                starting = it.starting, stopping = it.stopping)
        } + rootState.configServer.managedNetworks.map {
            NetworkSession(it.instanceId, it.instanceName, it.running, it.running, it.localCidr,
                it.nodes, error = it.error, remote = true, noTun = it.noTun)
        }
    } else ffiInstances.map {
        NetworkSession(it.instanceId, it.displayName, it.enabled, it.running, it.localCidr,
            it.nodes, error = it.error, remote = it.source == "web", noTun = it.noTun, activeVpn = it.activeVpn)
    }
    val current = configs.firstOrNull { it.id == rootSelectedConfigId }
        ?: configs.firstOrNull { it.isDefault } ?: configs.firstOrNull()
    val selected = sessions.firstOrNull { it.id == current?.id }
    val server = if (rootMode) rootState.configServer else ffiConfigServer
    val canStart = !rootMode || (rootAvailable && rootState.core.ready)
    fun setEnabled(session: NetworkSession, enabled: Boolean) {
        if (rootMode) {
            if (!enabled) rootController.stop(session.id)
            else configs.firstOrNull { it.id == session.id }?.let(rootController::start)
        } else onSetFfiInstanceEnabled(session.id, enabled)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header("运行状态", if (rootMode) "Root · ${sessions.count { it.enabled }} 个已启用" else "VPN", palette)
        }
        item {
            QCard(palette) {
                Text("当前配置", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                RootConfigSelect(configs, current?.id.orEmpty(), sessions.filter { it.enabled }.map { it.id }.toSet(), palette, onRootConfigSelected)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QButton(
                        text = when {
                            selected?.starting == true -> "启动中"
                            selected?.stopping == true -> "停止中"
                            selected?.enabled == true -> "停止此配置"
                            else -> "启动此配置"
                        },
                        palette = palette, modifier = Modifier.weight(1f),
                        danger = selected?.enabled == true,
                        enabled = current != null && selected?.starting != true && selected?.stopping != true && (selected?.enabled == true || canStart),
                        icon = if (selected?.enabled == true) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = {
                            if (selected?.enabled == true) setEnabled(selected, false)
                            else current?.let { if (rootMode) rootController.start(it) else onStart(it) }
                        }
                    )
                    QButton("编辑", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Edit,
                        enabled = current != null, onClick = { current?.let(onEdit) })
                }
                if (rootMode && !canStart) {
                    Spacer(Modifier.height(8.dp))
                    Text("请在设置中授权 Root 并安装核心。", color = palette.subText, fontSize = 12.sp)
                }
            }
        }
        if (!rootMode && (vpnPermissionRequired || runtime.statusText.contains("失败"))) {
            item {
                QCard(palette) {
                    Text(runtime.statusText, color = if (vpnPermissionRequired) palette.error else palette.subText, fontSize = 14.sp)
                    if (vpnPermissionRequired) {
                        Spacer(Modifier.height(8.dp))
                        QButton("授权 VPN", palette, icon = Icons.Rounded.Security, onClick = onRequestVpnAuthorization)
                    }
                }
            }
        }
        if (server.running || server.starting || server.error.isNotBlank() || server.managedNetworks.isNotEmpty()) {
            item {
                QCard(palette) {
                    Text("配置服务器", color = palette.text, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        server.error.isNotBlank() -> server.error
                        server.connected -> "已连接 · ${server.managedNetworks.size} 个下发网络"
                        server.running || server.starting -> "正在连接"
                        else -> "已断开 · 保留 ${server.managedNetworks.size} 个网络"
                    }, color = if (server.error.isNotBlank()) palette.error else palette.subText, fontSize = 12.sp)
                }
            }
        }
        if (rootMode) {
            items(sessions.filter { it.enabled || it.remote || it.error.isNotBlank() || it.id == current?.id }, key = { it.id }) { session ->
                SessionCard(palette, session, rootMode = true, canStart = canStart,
                    showToggle = !session.remote, onToggle = { setEnabled(session, !session.enabled) })
            }
        } else {
            // VPN mode is driven by the profile selector above: the Android VPN
            // is one shared interface, so only the selected profile is shown.
            selected?.takeIf { it.enabled || it.error.isNotBlank() }?.let { session ->
                item(key = "vpn-${session.id}") {
                    SessionCard(palette, session, rootMode = false, canStart = true,
                        showToggle = false, onToggle = {})
                }
            }
        }
        if (!rootMode && runtime.logs.isNotEmpty()) {
            item { LogSection(palette, runtime.logs, nodesExpanded, onNodesExpandedChange) }
        }
    }
}

@Composable
private fun SessionCard(
    palette: Palette,
    session: NetworkSession,
    rootMode: Boolean,
    canStart: Boolean,
    showToggle: Boolean,
    onToggle: () -> Unit
) {
    QCard(palette) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (session.running) palette.success else if (session.error.isNotBlank()) palette.error else palette.subText)
            Column(Modifier.weight(1f)) {
                Text(session.name, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${if (session.remote) "服务器下发" else "本地配置"} · ${if (session.noTun) "无 TUN" else if (rootMode) "Root TUN" else "VPN"}",
                    color = palette.subText, fontSize = 12.sp)
            }
            if (showToggle) {
                QButton(if (session.enabled) "停止" else "启用", palette, Modifier.width(78.dp), compact = true,
                    primary = !session.enabled, dangerText = session.enabled,
                    enabled = !session.starting && !session.stopping && (session.enabled || canStart),
                    onClick = onToggle)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(when {
            session.error.isNotBlank() -> session.error
            session.starting -> "正在启动"
            session.stopping -> "正在停止"
            session.cidr.isNotBlank() -> "已连接: ${session.cidr}"
            session.noTun && session.running -> "无 TUN 网络运行中"
            session.enabled -> "等待网络地址"
            else -> "已停用"
        }, color = if (session.error.isNotBlank()) palette.error else palette.subText, fontSize = 12.sp)
        if (session.enabled || session.nodes.isNotEmpty()) {
            var expanded by remember(session.id) { mutableStateOf(false) }
            NodeList(palette, session.nodes, expanded) { expanded = it }
        }
        if (session.logs.isNotEmpty()) {
            var expanded by remember(session.id) { mutableStateOf(false) }
            LogSection(palette, session.logs, expanded) { expanded = it }
        }
    }
}

private data class NetworkSession(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val running: Boolean,
    val cidr: String,
    val nodes: List<NodeInfo>,
    val logs: List<String> = emptyList(),
    val error: String = "",
    val remote: Boolean = false,
    val noTun: Boolean = false,
    val activeVpn: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false
)

@Composable
private fun RootConfigSelect(
    configs: List<NetworkConfig>,
    selectedId: String,
    runningIds: Set<String>,
    palette: Palette,
    onSelected: (String) -> Unit
) {
    PopupSelect(
        value = selectedId,
        options = configs.map { config ->
            PopupSelectOption(
                value = config.id,
                label = config.displayName,
                subtitle = config.networkName.ifBlank { "未填写网络名称" },
                running = config.id in runningIds
            )
        },
        palette = palette,
        onValue = onSelected
    )
}

@Composable
private fun NodeList(
    palette: Palette,
    nodes: List<NodeInfo>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("设备列表", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (nodes.isEmpty()) "等待节点信息" else "${nodes.size} 个设备",
                color = palette.subText,
                fontSize = 12.sp
            )
        }
        ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
    ) {
        Column {
            if (nodes.isEmpty()) {
                Text("等待节点信息", color = palette.subText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                nodes.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(node.hostname, color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${node.ip} · ${node.role}", color = palette.subText, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(if (node.latencyMs < 0) "待测" else "${node.latencyMs} ms", color = palette.subText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogSection(
    palette: Palette,
    logs: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("运行日志", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surfaceVariant)
                .padding(10.dp)
        ) {
            logs.takeLast(18).ifEmpty { listOf("暂无日志") }.forEach {
                Text(it, color = palette.subText, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    ffiInstances: List<FfiInstanceState>,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootMode: Boolean,
    onStart: (NetworkConfig) -> Unit,
    onSetFfiInstanceEnabled: (String, Boolean) -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    onNew: () -> Unit,
    onImportToml: () -> Unit,
    onDelete: (NetworkConfig) -> Unit,
    onDefault: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
    ) {
    var pendingDeleteConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var deletingConfigId by remember { mutableStateOf<String?>(null) }
    val rootState = rootController.state
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                "配置文件",
                "${configs.size} 份配置",
                palette
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QButton("新建", palette, Modifier.weight(1f), icon = Icons.Rounded.Add, onClick = onNew)
                QButton("导入 TOML", palette, Modifier.weight(1f), primary = false, onClick = onImportToml)
            }
        }
        items(configs, key = { it.id }) { cfg ->
            val rootInstance = if (rootMode) rootState.instances.firstOrNull { it.configId == cfg.id } else null
            val ffiInstance = if (!rootMode) ffiInstances.firstOrNull { it.instanceId == cfg.id } else null
            val runningHere = if (rootMode) {
                rootInstance?.running == true || rootInstance?.starting == true || rootInstance?.stopping == true
            } else {
                ffiInstance?.enabled == true
            }
            val runnable = if (rootMode) rootAvailable else true
            AnimatedVisibility(
                visible = cfg.id != deletingConfigId,
                exit = shrinkVertically(animationSpec = tween(280)) + fadeOut(tween(200))
            ) {
            QCard(palette, modifier = Modifier.animateContentSize(tween(280))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cfg.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cfg.networkName.ifBlank { "尚未填写网络名称" }, color = palette.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DefaultPill(palette, selected = cfg.isDefault, onClick = { if (!cfg.isDefault) onDefault(cfg) })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton(
                        text = when {
                            rootInstance?.starting == true -> "启动中"
                            rootInstance?.stopping == true -> "停止中"
                            runningHere -> "停止"
                            rootMode && !rootAvailable -> "无 Root"
                            else -> "运行"
                        },
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        danger = runningHere,
                        enabled = runningHere || runnable,
                        icon = if (runningHere) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = {
                            if (runningHere) {
                                if (rootMode) rootController.stop(cfg.id) else onSetFfiInstanceEnabled(cfg.id, false)
                            } else {
                                if (rootMode) rootController.start(cfg) else onStart(cfg)
                            }
                        }
                    )
                    QButton("编辑", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Edit, onClick = { onEdit(cfg) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton("导出", palette, Modifier.weight(1f), primary = false, onClick = { onExport(cfg) })
                    QButton("删除", palette, Modifier.weight(1f), primary = false, dangerText = true, icon = Icons.Rounded.Delete, enabled = !runningHere, onClick = { pendingDeleteConfig = cfg })
                }
            }
            }
        }
    }

            pendingDeleteConfig?.let { cfg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteConfig = null },
            title = { Text("确认删除", color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要删除配置「${cfg.displayName}」吗？此操作不可撤销。", color = palette.subText, lineHeight = 20.sp) },
            confirmButton = {
                QButton("删除", palette, compact = true, danger = true) {
                    deletingConfigId = cfg.id
                    pendingDeleteConfig = null
                }
            },
            dismissButton = {
                QButton("取消", palette, primary = false, compact = true) { pendingDeleteConfig = null }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
    LaunchedEffect(deletingConfigId) {
        deletingConfigId?.let { id ->
            delay(300)
            val cfg = configs.firstOrNull { it.id == id }
            if (cfg != null) onDelete(cfg)
            deletingConfigId = null
        }
    }
}


@Composable
private fun ServersPage(
    palette: Palette,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    rootMode: Boolean,
    rootAvailable: Boolean,
    settings: AppSettings,
    configServer: RootConfigServerState,
    onSettings: (AppSettings) -> Unit,
    onConnectConfigServer: () -> Unit,
    onDisconnectConfigServer: () -> Unit,
    onSaveFfiConfigServer: (String) -> Unit,
    onSaveUserServers: (List<ServerEntry>) -> Unit,
    onAddOfficialServer: (ServerEntry) -> Unit,
    onDownloadUsers: (String) -> Unit,
    onSyncOfficial: () -> Unit,
    officialSyncInProgress: Boolean,
    officialSyncMessage: String?,
    officialSyncFailed: Boolean
) {
    var favoriteOpen by remember { mutableStateOf(false) }
    var officialOpen by remember { mutableStateOf(false) }
    var consoleOpen by remember { mutableStateOf(false) }
    var consoleLogsOpen by remember { mutableStateOf(false) }
    var ffiDraft by remember { mutableStateOf(settings.ffiConfigServerUrl) }
    var source by remember { mutableStateOf("") }

    LaunchedEffect(settings.ffiConfigServerUrl) {
        ffiDraft = settings.ffiConfigServerUrl
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                "服务器",
                "配置服务器、用户收藏与官方源",
                palette
            )
        }
        item {
                QCard(palette) {
                    val statusText = when {
                        rootMode && !rootAvailable -> "Root 不可用"
                        configServer.stopping -> "正在断开"
                        configServer.starting -> "正在启动"
                        configServer.connected -> "已连接"
                        configServer.running -> "正在连接"
                        configServer.error.isNotBlank() -> "连接失败"
                        else -> "未连接"
                    }
                    val statusColor = when {
                        configServer.connected -> palette.success
                        configServer.error.isNotBlank() || (rootMode && !rootAvailable) -> palette.error
                        else -> palette.subText
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("配置服务器", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                if (configServer.managedNetworks.isEmpty()) statusText else "$statusText · ${configServer.managedNetworks.size} 个下发网络",
                                color = statusColor,
                                fontSize = 12.sp
                            )
                        }
                        StatusDot(statusColor)
                        Spacer(Modifier.width(8.dp))
                        ExpandArrow(consoleOpen, palette) { consoleOpen = !consoleOpen }
                    }
                    AnimatedVisibility(
                        visible = consoleOpen,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
                    ) {
                        Column(Modifier.padding(top = 10.dp)) {
                            if (rootMode) {
                                FieldBlock("完整配置服务器 URL", palette) {
                                    QTextField(
                                        value = settings.configServerUrl,
                                        placeholder = "udp://host:22020/admin",
                                        palette = palette,
                                        onValueChange = { onSettings(settings.copy(configServerUrl = it)) }
                                    )
                                }
                                FieldBlock("主机名", palette) {
                                    QTextField(
                                        value = settings.configServerHostname,
                                        placeholder = "留空时使用设备名",
                                        palette = palette,
                                        onValueChange = { onSettings(settings.copy(configServerHostname = it)) }
                                    )
                                }
                                FieldBlock("机器 ID", palette) {
                                    QTextField(
                                        value = settings.configServerMachineId,
                                        placeholder = "留空时使用 Android 设备 ID",
                                        palette = palette,
                                        onValueChange = { onSettings(settings.copy(configServerMachineId = it)) }
                                    )
                                }
                                SwitchRow("配置服务器加密（Secure Mode）", settings.configServerSecureMode, palette) {
                                    onSettings(settings.copy(configServerSecureMode = it))
                                }
                                SwitchRow("启动时自动连接", settings.configServerAutoConnect, palette) {
                                    onSettings(settings.copy(configServerAutoConnect = it))
                                }
                            } else {
                                FieldBlock("配置服务器地址", palette) {
                                    QTextField(
                                        value = ffiDraft,
                                        placeholder = "用户名或 udp://host:22020/username",
                                        palette = palette,
                                        onValueChange = { ffiDraft = it }
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                QButton(
                                    text = "保存",
                                    palette = palette,
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Check,
                                    enabled = !configServer.starting && !configServer.stopping &&
                                        (ffiDraft.trim() != configServer.serverUrl.trim() ||
                                            (ffiDraft.isNotBlank() && !configServer.running)),
                                    onClick = { onSaveFfiConfigServer(ffiDraft.trim()) }
                                )
                            }
                            if (configServer.error.isNotBlank()) {
                                Text(configServer.error, color = palette.error, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            if (configServer.managedNetworks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                configServer.managedNetworks.forEach { network ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StatusDot(if (network.running) palette.success else palette.subText)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                network.instanceName.ifBlank { network.instanceId.ifBlank { "下发网络" } },
                                                color = palette.text,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                when {
                                                    network.error.isNotBlank() -> network.error
                                                    !network.running -> "已停用"
                                                    network.noTun -> "无 TUN · 运行中"
                                                    else -> network.localCidr.ifBlank { "等待虚拟 IP" }
                                                },
                                                color = if (network.error.isNotBlank()) palette.error else palette.subText,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                            if (rootMode) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    QButton(
                                        text = if (configServer.starting) "启动中" else "连接",
                                        palette = palette,
                                        modifier = Modifier.weight(1f),
                                        enabled = rootAvailable && !configServer.running && !configServer.starting && !configServer.stopping && isFullConfigServerUrl(settings.configServerUrl),
                                        onClick = onConnectConfigServer
                                    )
                                    QButton(
                                        text = if (configServer.stopping) "断开中" else "断开",
                                        palette = palette,
                                        modifier = Modifier.weight(1f),
                                        primary = false,
                                        dangerText = true,
                                        enabled = configServer.running || configServer.starting,
                                        onClick = onDisconnectConfigServer
                                    )
                                }
                            }
                            if (configServer.logs.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                LogSection(
                                    palette = palette,
                                    logs = configServer.logs,
                                    expanded = consoleLogsOpen,
                                    onExpandedChange = { consoleLogsOpen = it }
                                )
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
                        Text("用户收藏", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("${userServers.size} 个服务器", color = palette.subText, fontSize = 12.sp)
                    }
                    ExpandArrow(favoriteOpen, palette) { favoriteOpen = !favoriteOpen }
                }
                AnimatedVisibility(
                    visible = favoriteOpen,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
                ) {
                    Column(Modifier.padding(top = 12.dp)) {
                        ListEditor(
                            title = "收藏地址",
                            values = userServers.map { it.address },
                            placeholder = "tcp://server.example.com:11010",
                            palette = palette,
                            onValues = { values ->
                                onSaveUserServers(
                                    values.map { address ->
                                        ServerEntry(serverNameFromAddress(address), address)
                                    }
                                )
                            }
                        )
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
                    ExpandArrow(officialOpen, palette) { officialOpen = !officialOpen }
                }
                AnimatedVisibility(
                    visible = officialOpen,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
                ) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QButton(
                            if (officialSyncInProgress) "同步中" else "立即同步公共服务器",
                            palette,
                            Modifier.fillMaxWidth(),
                            primary = false,
                            icon = Icons.Rounded.Refresh,
                            iconRotation = spinOnceRotation(officialSyncInProgress),
                            enabled = !officialSyncInProgress,
                            onClick = onSyncOfficial
                        )
                        officialSyncMessage?.let { message ->
                            Text(
                                message,
                                color = if (officialSyncFailed) palette.error else palette.success,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        officialServers.ifEmpty { listOf(ServerEntry("暂无官方服务器", "")) }.forEach { server ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
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
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootChecking: Boolean,
    onSettings: (AppSettings) -> Unit,
    onRefreshRoot: () -> Unit,
    onImportCoreZip: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    var disclaimer by remember { mutableStateOf(false) }
    var logExpanded by remember { mutableStateOf(false) }
    var appLogRefreshing by remember { mutableStateOf(false) }
    var appLogText by remember { mutableStateOf(AppDiagnostics.recent(8_000)) }
    val scope = rememberCoroutineScope()
    val core = rootController.state.core

    LaunchedEffect(logExpanded) {
        if (logExpanded) {
            appLogText = withContext(Dispatchers.IO) { AppDiagnostics.recent(8_000) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header("设置", "运行模式与官方核心", palette) }
        item {
            QCard(palette) {
                Text("运行模式", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        text = "VPN 模式 (FFI)",
                        selected = !settings.rootModeEnabled,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings(settings.copy(rootModeEnabled = false)) }
                    )
                    ModeButton(
                        text = "Root 模式 (官方核心)",
                        selected = settings.rootModeEnabled,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings(settings.copy(rootModeEnabled = true)) }
                    )
                }
                if (settings.rootModeEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(if (rootAvailable) palette.success else palette.error)
                        Text(
                            if (rootAvailable) "Root可用" else "未检测到 Root 管理器",
                            color = if (rootAvailable) palette.success else palette.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        QButton(
                            text = if (rootChecking) "检测中" else "重新检测",
                            palette = palette,
                            modifier = Modifier.width(104.dp),
                            compact = true,
                            primary = false,
                            icon = Icons.Rounded.Refresh,
                            iconRotation = spinOnceRotation(rootChecking),
                            enabled = !rootChecking,
                            onClick = onRefreshRoot
                        )
                    }
                    if (!rootAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请先在 Root 管理器中授权 MoonTier（KernelSU/Magisk/APatch），再重新检测。",
                            color = palette.subText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
        item {
            QCard(palette) {
                SwitchRow("自动同步官方服务器", settings.autoSyncOfficialServers, palette) {
                    onSettings(settings.copy(autoSyncOfficialServers = it))
                }
                SwitchRow("深色模式", settings.darkMode, palette) {
                    onSettings(settings.copy(darkMode = it))
                }
                SwitchRow("自动配置出口节点路由", settings.exitNodeAutoRoutes, palette) {
                    if (it) disclaimer = true else onSettings(settings.copy(exitNodeAutoRoutes = false))
                }
            }
        }
        item {
            QCard(palette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { logExpanded = !logExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("日志", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    ExpandArrow(logExpanded, palette) { logExpanded = !logExpanded }
                }
                AnimatedVisibility(
                    visible = logExpanded,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(CoreLogLevel.OFF, CoreLogLevel.NORMAL, CoreLogLevel.DEBUG).forEach { level ->
                                ModeButton(
                                    text = CoreLogLevel.label(level),
                                    selected = CoreLogLevel.normalize(settings.coreLogLevel) == level,
                                    palette = palette,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSettings(settings.copy(coreLogLevel = level)) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        ActionBar(palette) {
                            QButton(
                                text = if (appLogRefreshing) "刷新中" else "刷新",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                primary = false,
                                enabled = !appLogRefreshing,
                                icon = Icons.Rounded.Refresh,
                                iconRotation = spinOnceRotation(appLogRefreshing),
                                onClick = {
                                    scope.launch {
                                        appLogRefreshing = true
                                        try {
                                            appLogText = withContext(Dispatchers.IO) {
                                                AppDiagnostics.recent(8_000)
                                            }
                                            delay(80)
                                        } finally {
                                            appLogRefreshing = false
                                        }
                                    }
                                }
                            )
                            QButton(
                                text = "导出日志",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                icon = Icons.Rounded.Description,
                                onClick = onExportDiagnostics
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (appLogText.isBlank()) "暂无记录" else appLogText,
                            color = palette.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            maxLines = 30,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (settings.rootModeEnabled) {
            item {
                QCard(palette) {
                    Text("下载核心", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("下载来源", color = palette.subText, fontSize = 12.sp)
                    if (core.checking || core.installing) {
                        Text(settings.coreReleaseChannel.label, color = palette.text, fontSize = 14.sp)
                    } else {
                        QSelect(settings.coreReleaseChannel.label, CoreReleaseChannel.entries.map { it.label }, palette) { label ->
                            onSettings(settings.copy(coreReleaseChannel = CoreReleaseChannel.entries.first { it.label == label }))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (core.ready) palette.success else palette.subText)
                    Text(
                        if (core.ready) "已安装 ${core.installedVersion}" else "尚未安装",
                        color = if (core.ready) palette.success else palette.subText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    }
                    if (core.latestVersion.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${settings.coreReleaseChannel.label}: ${core.latestVersion}",
                        color = palette.subText,
                        fontSize = 12.sp,
                    )
                    }
                    if (core.installing) {
                    Spacer(Modifier.height(8.dp))
                    Text("正在处理 ${core.progress}%", color = palette.text, fontSize = 13.sp)
                    }
                    if (core.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(core.message, color = palette.subText, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    ActionBar(palette) {
                    QButton(
                        text = if (core.checking) "检查中" else "检查更新",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = false,
                        enabled = !core.checking && !core.installing,
                        icon = Icons.Rounded.Refresh,
                        iconRotation = spinOnceRotation(core.checking),
                        onClick = { rootController.checkCoreUpdate() }
                    )
                    QButton(
                        text = if (core.installing) "下载中" else "下载核心",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        enabled = !core.installing,
                        icon = Icons.Rounded.Download,
                        onClick = { rootController.installCore() }
                    )
                    QButton(
                        text = "导入 ZIP",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = false,
                        enabled = !core.installing,
                        icon = Icons.Rounded.Description,
                        onClick = onImportCoreZip
                    )
                    if (core.hasRollback) {
                        QButton(
                            text = "回退核心",
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            primary = false,
                            enabled = !core.installing,
                            icon = Icons.Rounded.Refresh,
                            onClick = { rootController.rollbackCore() }
                        )
                    }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Root 模式自动检查更新", settings.coreAutoUpdate, palette) {
                        onSettings(settings.copy(coreAutoUpdate = it))
                    }
                    Text(
                        "Root 模式直接运行官方发布的 aarch64 easytier-core，可单独更新，不依赖 FFI。",
                        color = palette.subText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    if (disclaimer) {
        AlertDialog(
            onDismissRequest = { disclaimer = false },
            confirmButton = {
                QButton("\u542f\u7528", palette, compact = true) {
                    onSettings(settings.copy(exitNodeAutoRoutes = true))
                    disclaimer = false
                }
            },
            dismissButton = {
                QButton("\u53d6\u6d88", palette, primary = false, compact = true) { disclaimer = false }
            },
            title = { Text("\u51fa\u53e3\u8282\u70b9\u8def\u7531", color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "\u542f\u7528\u540e\u4f1a\u901a\u8fc7 EasyTier \u5411 Android VPN \u4e0b\u53d1\u9ed8\u8ba4 IPv4 \u8def\u7531\u3002\u8bf7\u5728\u786e\u8ba4\u51fa\u53e3\u8282\u70b9\u53ef\u4fe1\u4e14\u7b26\u5408\u9884\u671f\u540e\u518d\u5f00\u542f\u3002",
                    color = palette.subText,
                    lineHeight = 20.sp
                )
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.surfaceVariant,
        animationSpec = tween(220),
        label = "mode-background"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) palette.onAccent else palette.text,
        animationSpec = tween(180),
        label = "mode-text"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 0.dp else 0.7.dp,
        animationSpec = tween(220),
        label = "mode-border"
    )
    val contentScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = tween(220),
        label = "mode-scale"
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { scaleX = contentScale; scaleY = contentScale }
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                BorderStroke(borderWidth, palette.border),
                RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun spinOnceRotation(active: Boolean): Float {
    var turns by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) turns += 1
    }
    return animateFloatAsState(
        targetValue = turns * 360f,
        animationSpec = tween(600),
        label = "refresh-spin"
    ).value
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
    val editorScope = rememberCoroutineScope()
    var validating by remember { mutableStateOf(false) }
    var rawTomlOpen by remember { mutableStateOf(false) }
    var rawTomlText by remember(initial.id) { mutableStateOf(TomlCodec.build(initial)) }
    var rawTomlDirty by remember(initial.id) { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var protocolOpen by remember { mutableStateOf(false) }
    var p2pOpen by remember { mutableStateOf(false) }
    var perfOpen by remember { mutableStateOf(false) }
    var serviceOpen by remember { mutableStateOf(false) }
    var aclOpen by remember { mutableStateOf(false) }
    var addressOpen by remember { mutableStateOf(true) }
    var gatewayOpen by remember { mutableStateOf(false) }
    var serverPicker by remember { mutableStateOf<ServerPickerState?>(null) }

    fun updateAdvanced(transform: (AdvancedNetworkConfig) -> AdvancedNetworkConfig) {
        cfg = cfg.copy(advanced = transform(cfg.advanced))
    }

    LaunchedEffect(cfg, rawTomlDirty) {
        if (!rawTomlDirty) runCatching { TomlCodec.build(cfg) }.onSuccess { rawTomlText = it }
    }

    fun parseRawTomlForEditor(): NetworkConfig? {
        val parsed = runCatching { TomlCodec.parse(rawTomlText) }.getOrElse {
            editorError = "\u539f\u59cb TOML \u89e3\u6790\u5931\u8d25" to (it.message ?: "\u65e0\u6cd5\u89e3\u6790\u5f53\u524d TOML\u3002")
            return null
        }
        return parsed.copy(id = cfg.id, isDefault = cfg.isDefault)
    }

    fun useCurrentConfig(action: (NetworkConfig) -> Unit) {
        if (validating) return
        val target = (if (rawTomlDirty) parseRawTomlForEditor() else cfg) ?: return
        validating = true
        editorScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { NativeEasyTier.parseConfig(TomlCodec.build(target)) }
            }
            validating = false
            result.onSuccess {
                rawTomlDirty = false
                action(target)
            }.onFailure {
                editorError = "配置无效" to (it.message ?: "请检查地址、密钥及数值设置。")
            }
        }
    }

    fun saveCurrentConfig() { useCurrentConfig(onSave) }
    fun exportCurrentConfig() { useCurrentConfig(onExport) }

    fun applyRawTomlToForm() {
        val parsed = parseRawTomlForEditor() ?: return
        cfg = parsed
        rawTomlDirty = false
    }

    BackHandler {
        onBack()
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
                Spacer(Modifier.height(16.dp))
                ActionBar(palette) {
                    QButton("保存", palette, Modifier.weight(1f), compact = true, enabled = !validating, icon = Icons.Rounded.Check) { saveCurrentConfig() }
                    QButton("不保存", palette, Modifier.weight(1f), primary = false, compact = true) { onBack() }
                    QButton("导出", palette, Modifier.weight(1f), primary = false, compact = true, enabled = !validating) { exportCurrentConfig() }
                }
            }
        }

        item {
            QCard(palette) {
                SmallLabel("基础设置", palette)
                FieldBlock("主机名", palette) {
                    QTextField(cfg.hostname, "留空时使用系统设备名", palette) { cfg = cfg.copy(hostname = it) }
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
                SwitchRow("安全模式", cfg.advanced.secureMode, palette) { value -> updateAdvanced { it.copy(secureMode = value) } }
                if (cfg.advanced.secureMode) {
                    Text("使用密钥认证节点。密码留空时通过凭据加入网络；密钥留空由核心生成。", color = palette.subText, fontSize = 12.sp, lineHeight = 18.sp)
                    FieldBlock("节点私钥 / 访问凭据", palette) {
                        QTextField(cfg.advanced.privateKey, "Base64 私钥或管理员发放的凭据", palette, password = true) { value -> updateAdvanced { it.copy(privateKey = value) } }
                    }
                    FieldBlock("节点公钥", palette) {
                        QTextField(cfg.advanced.publicKey, "留空时从私钥推导", palette) { value -> updateAdvanced { it.copy(publicKey = value) } }
                    }
                    cfg.peerUrls.forEach { uri ->
                        FieldBlock("服务器公钥 · $uri", palette) {
                            QTextField(cfg.advanced.peerKeys[uri].orEmpty(), "可选，用于校验对端身份", palette) { value -> updateAdvanced { it.copy(peerKeys = it.peerKeys + (uri to value)) } }
                        }
                    }
                    ManagedCredentialsEditor(cfg.advanced.managedCredentials, palette) { value -> updateAdvanced { it.copy(managedCredentials = value) } }
                }
                SwitchRow("低延迟优先", cfg.latencyFirst, palette) { cfg = cfg.copy(latencyFirst = it) }
                SwitchRow("私有模式", cfg.enablePrivateMode, palette) { cfg = cfg.copy(enablePrivateMode = it) }
            }
        }

        item {
            ExpandableQCard("地址分配", addressOpen, { addressOpen = it }, palette) {
                SwitchRow("IPv4 DHCP 自动分配", cfg.dhcp, palette) { cfg = cfg.copy(dhcp = it) }
                if (cfg.dhcp) {
                    FieldBlock("IPv4 DHCP 地址段", palette) {
                        QTextField(cfg.advanced.dhcpCidr, "留空自动选择，例如 10.126.126.0/24", palette) { value -> updateAdvanced { it.copy(dhcpCidr = value) } }
                    }
                } else {
                    FieldBlock("虚拟 IPv4 地址", palette) {
                        QTextField(cfg.ipv4, "例如 10.126.126.2/24", palette) { cfg = cfg.copy(ipv4 = it) }
                    }
                }
                SwitchRow("启用 IPv6 连接", !cfg.disableIpv6, palette) { cfg = cfg.copy(disableIpv6 = !it) }
                Text("IPv6 连接控制底层传输；虚拟 IPv6 地址单独配置。", color = palette.subText, fontSize = 12.sp)
                SwitchRow("IPv6 DHCP 自动分配", cfg.advanced.dhcpv6, palette) { value -> updateAdvanced { it.copy(dhcpv6 = value) } }
                if (cfg.advanced.dhcpv6) {
                    FieldBlock("IPv6 DHCP 地址段", palette) {
                        QTextField(cfg.advanced.dhcpv6Cidr, "留空自动选择，例如 fd12:3456::/64", palette) { value -> updateAdvanced { it.copy(dhcpv6Cidr = value) } }
                    }
                } else {
                    FieldBlock("虚拟 IPv6 地址", palette) {
                        QTextField(cfg.advanced.ipv6, "例如 fd12:3456::2/64", palette) { value -> updateAdvanced { it.copy(ipv6 = value) } }
                    }
                }
                SwitchRow("自动获取公网 IPv6 地址", cfg.advanced.ipv6PublicAddrAuto, palette) { value -> updateAdvanced { it.copy(ipv6PublicAddrAuto = value) } }
                SwitchRow("提供公网 IPv6 前缀", cfg.advanced.ipv6PublicAddrProvider, palette) { value -> updateAdvanced { it.copy(ipv6PublicAddrProvider = value) } }
                if (cfg.advanced.ipv6PublicAddrProvider) {
                    Text("前缀分发需要系统接口支持，在 Root 模式下使用。", color = palette.subText, fontSize = 12.sp)
                    FieldBlock("公网 IPv6 前缀", palette) {
                        QTextField(cfg.advanced.ipv6PublicAddrPrefix, "留空自动检测，例如 2001:db8:1234::/64", palette) { value -> updateAdvanced { it.copy(ipv6PublicAddrPrefix = value) } }
                    }
                }
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
                FieldBlock("数据压缩", palette) {
                    QSelect(cfg.advanced.dataCompressAlgo, listOf("none", "zstd"), palette) { value -> updateAdvanced { it.copy(dataCompressAlgo = value) } }
                }
                FieldBlock("默认加密协议", palette) {
                    QSelect(cfg.encryptionAlgorithm, listOf("aes-gcm", "aes-256-gcm", "chacha20", "xor"), palette) { cfg = cfg.copy(encryptionAlgorithm = it) }
                }
            }
        }

        item {
            ExpandableQCard("P2P 与 NAT", p2pOpen, { p2pOpen = it }, palette) {
                SwitchRow("仅 P2P", cfg.p2pOnly, palette) { cfg = cfg.copy(p2pOnly = it) }
                SwitchRow("禁用 P2P", cfg.disableP2p, palette) { cfg = cfg.copy(disableP2p = it) }
                SwitchRow("需要 P2P", cfg.needP2p, palette) { cfg = cfg.copy(needP2p = it) }
                SwitchRow("按需 P2P", cfg.lazyP2p, palette) { cfg = cfg.copy(lazyP2p = it) }
                SwitchRow("禁用 UDP 打洞", cfg.disableUdpHolePunching, palette) { cfg = cfg.copy(disableUdpHolePunching = it) }
                SwitchRow("禁用 TCP 打洞", cfg.disableTcpHolePunching, palette) { cfg = cfg.copy(disableTcpHolePunching = it) }
                SwitchRow("禁用 UPnP", cfg.disableUpnp, palette) { cfg = cfg.copy(disableUpnp = it) }
                SwitchRow("禁用对称 NAT 打洞", cfg.disableSymHolePunching, palette) { cfg = cfg.copy(disableSymHolePunching = it) }
                SwitchRow("禁止转发数据包", cfg.advanced.disableRelayData, palette) { value -> updateAdvanced { it.copy(disableRelayData = value) } }
                SwitchRow("优先使用对等节点中继", cfg.advanced.preferPeerRelay, palette) { value -> updateAdvanced { it.copy(preferPeerRelay = value) } }
                SwitchRow("启用 UDP 广播转发", cfg.advanced.enableUdpBroadcastRelay, palette) { value -> updateAdvanced { it.copy(enableUdpBroadcastRelay = value) } }
                SwitchRow("转发 RPC 包", cfg.relayAllPeerRpc, palette) { cfg = cfg.copy(relayAllPeerRpc = it) }
                SwitchRow("仅使用物理网卡", cfg.bindDevice, palette) { cfg = cfg.copy(bindDevice = it) }
                SmallLabel("NAT 探测", palette)
                ListEditor("UDP STUN 服务器", cfg.stunServers, "stun.example.com:3478", palette) { cfg = cfg.copy(stunServers = it) }
                ListEditor("TCP STUN 服务器", cfg.advanced.tcpStunServers, "stun.example.com:3478", palette) { value -> updateAdvanced { it.copy(tcpStunServers = value) } }
                ListEditor("IPv6 STUN 服务器", cfg.advanced.stunServersV6, "[2001:db8::1]:3478", palette) { value -> updateAdvanced { it.copy(stunServersV6 = value) } }
            }
        }

        item {
            ExpandableQCard("网络服务", serviceOpen, { serviceOpen = it }, palette) {
                SwitchRow("启用出口节点", cfg.enableExitNode, palette) { cfg = cfg.copy(enableExitNode = it) }
                SwitchRow("系统转发", cfg.proxyForwardBySystem, palette) { cfg = cfg.copy(proxyForwardBySystem = it) }
                SwitchRow("启用魔法 DNS", cfg.enableMagicDns, palette) { cfg = cfg.copy(enableMagicDns = it) }
                if (cfg.enableMagicDns) {
                    FieldBlock("魔法 DNS 域名后缀", palette) {
                        QTextField(cfg.advanced.tldDnsZone, "默认 et.net.", palette) { value -> updateAdvanced { it.copy(tldDnsZone = value) } }
                    }
                }
                SwitchRow("启用网络白名单", cfg.enableRelayNetworkWhitelist, palette) { cfg = cfg.copy(enableRelayNetworkWhitelist = it) }
                AnimatedVisibility(
                    cfg.enableRelayNetworkWhitelist,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        ListEditor("网络白名单", cfg.relayNetworkWhitelist, "网络名称", palette) {
                            cfg = cfg.copy(relayNetworkWhitelist = it)
                        }
                    }
                }
                ListEditor("映射监听地址", cfg.advanced.mappedListeners, "tcp://public.example.com:11010", palette) { value -> updateAdvanced { it.copy(mappedListeners = value) } }
                ListEditor("监听地址", cfg.listenerUrls, "tcp://0.0.0.0:11010", palette) {
                    cfg = cfg.copy(listenerUrls = it)
                }
                ProxyNetworksEditor(cfg.proxyCidrs, cfg.advanced.proxyMappings, palette) { cidrs, mappings ->
                    cfg = cfg.copy(proxyCidrs = cidrs, advanced = cfg.advanced.copy(proxyMappings = mappings))
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
        item {
            ExpandableQCard("代理与端口转发", gatewayOpen, { gatewayOpen = it }, palette) {
                FieldBlock("SOCKS5 代理监听", palette) {
                    QTextField(cfg.advanced.socks5Proxy, "例如 socks5://127.0.0.1:1080", palette) { value -> updateAdvanced { it.copy(socks5Proxy = value) } }
                }
                PortForwardsEditor(cfg.advanced.portForwards, palette) { value -> updateAdvanced { it.copy(portForwards = value) } }
                SwitchRow("启用 WireGuard 接入", cfg.advanced.enableVpnPortal, palette) { value -> updateAdvanced { it.copy(enableVpnPortal = value) } }
                if (cfg.advanced.enableVpnPortal) {
                    FieldBlock("WireGuard 监听地址", palette) {
                        QTextField(cfg.advanced.vpnPortalListen, "0.0.0.0:22022", palette) { value -> updateAdvanced { it.copy(vpnPortalListen = value) } }
                    }
                    FieldBlock("WireGuard 私钥", palette) {
                        QTextField(cfg.advanced.vpnPortalPrivateKey, "留空自动生成", palette, password = true) { value -> updateAdvanced { it.copy(vpnPortalPrivateKey = value) } }
                    }
                    VpnPortalClientsEditor(cfg.advanced.vpnPortalClients, palette) { value -> updateAdvanced { it.copy(vpnPortalClients = value) } }
                }
            }
        }
        item {
            ExpandableQCard("性能与系统", perfOpen, { perfOpen = it }, palette) {
                SwitchRow("启用多线程", cfg.multiThread, palette) { cfg = cfg.copy(multiThread = it) }
                if (cfg.multiThread) {
                    FieldBlock("工作线程数", palette) {
                        QTextField(cfg.advanced.multiThreadCount, "留空使用核心默认值", palette) { value -> updateAdvanced { it.copy(multiThreadCount = value) } }
                    }
                }
                FieldBlock("接收限速（字节 / 秒）", palette) {
                    QTextField(cfg.advanced.instanceRecvBpsLimit, "留空不限制", palette) { value -> updateAdvanced { it.copy(instanceRecvBpsLimit = value) } }
                }
                FieldBlock("其他网络中继限速（字节 / 秒）", palette) {
                    QTextField(cfg.advanced.foreignRelayBpsLimit, "留空不限制", palette) { value -> updateAdvanced { it.copy(foreignRelayBpsLimit = value) } }
                }
                FieldBlock("TUN 设备名称（Root）", palette) {
                    QTextField(cfg.advanced.devName, "留空自动命名", palette) { value -> updateAdvanced { it.copy(devName = value) } }
                }
                FieldBlock("出口 Socket Mark（Root）", palette) {
                    QTextField(cfg.advanced.socketMark, "可选，十进制整数", palette) { value -> updateAdvanced { it.copy(socketMark = value) } }
                }
                SwitchRow("使用用户态协议栈", cfg.useSmoltcp, palette) { cfg = cfg.copy(useSmoltcp = it) }
                SwitchRow("无 TUN 模式", cfg.noTun, palette) { cfg = cfg.copy(noTun = it) }
                FieldBlock("MTU 值", palette) {
                    QTextField(if (cfg.mtu > 0) cfg.mtu.toString() else "", "最大 1380，留空使用默认", palette) {
                        val mtu = it.trim().toIntOrNull()?.coerceIn(0, 1380) ?: 0
                        cfg = cfg.copy(mtu = mtu)
                    }
                }
            }
        }
        item {
            ExpandableQCard("访问控制 (ACL)", aclOpen, { aclOpen = it }, palette) {
                ListEditor("TCP 访问白名单", cfg.advanced.tcpWhitelist, "例如 80 或 1000-2000，留空不限制", palette) { value -> updateAdvanced { it.copy(tcpWhitelist = value) } }
                ListEditor("UDP 访问白名单", cfg.advanced.udpWhitelist, "例如 53 或 1000-2000，留空不限制", palette) { value -> updateAdvanced { it.copy(udpWhitelist = value) } }
                Text(
                    "\u8fd9\u91cc\u76f4\u63a5\u7f16\u8f91 ACL \u7684\u539f\u59cb TOML\uff0c\u4fdd\u5b58\u65f6\u4f1a\u539f\u6837\u9644\u52a0\u5230\u914d\u7f6e\u540e\u9762\uff0c\u4ee5\u4fbf\u540e\u7eed\u8ddf\u8fdb\u5b98\u65b9 ACL \u5b57\u6bb5\u53d8\u66f4\u3002",
                    color = palette.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                QTextField(
                    value = cfg.aclToml,
                    placeholder = "[acl]\ndefault_action = \"accept\"",
                    palette = palette,
                    modifier = Modifier.heightIn(min = 160.dp),
                    singleLine = false,
                    onValueChange = { cfg = cfg.copy(aclToml = it) }
                )
            }
        }
        item {
            ExpandableQCard("\u539f\u59cb TOML", rawTomlOpen, { rawTomlOpen = it }, palette) {
                Text(
                    "\u8fd9\u91cc\u53ea\u5199\u51fa\u4e0e\u6838\u5fc3\u9ed8\u8ba4\u503c\u4e0d\u540c\u7684\u9009\u9879\uff0c\u4fdd\u6301\u914d\u7f6e\u6587\u4ef6\u7b80\u77ed\u3002\u4fee\u6539\u540e\u5148\u70b9\u201c\u5e94\u7528\u5230\u8868\u5355\u201d\uff0c\u518d\u70b9\u4e0a\u65b9\u7684\u201c\u4fdd\u5b58\u201d\u3002",
                    color = palette.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                QTextField(
                    value = rawTomlText,
                    placeholder = "instance_id = \"...\"",
                    palette = palette,
                    modifier = Modifier.heightIn(min = 220.dp),
                    singleLine = false,
                    onValueChange = {
                        rawTomlText = it
                        rawTomlDirty = true
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QButton("\u5e94\u7528\u5230\u8868\u5355", palette, Modifier.weight(1f), primary = false, compact = true) {
                        applyRawTomlToForm()
                    }
                    QButton("\u7528\u8868\u5355\u8986\u76d6", palette, Modifier.weight(1f), primary = false, compact = true) {
                        runCatching { TomlCodec.build(cfg) }.onSuccess {
                            rawTomlText = it
                            rawTomlDirty = false
                        }.onFailure { editorError = "配置无效" to (it.message ?: "请检查表单设置。") }
                    }
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

    editorError?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { editorError = null },
            confirmButton = {
                QButton("\u786e\u5b9a", palette, compact = true) { editorError = null }
            },
            title = { Text(title, color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = { Text(body, color = palette.subText, lineHeight = 20.sp) },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
private fun <T> ConfigRulesEditor(
    title: String,
    values: List<T>,
    newItem: () -> T,
    palette: Palette,
    onValues: (List<T>) -> Unit,
    fields: @Composable ColumnScope.(T, (T) -> Unit) -> Unit
) {
    SmallLabel(title, palette)
    values.forEachIndexed { index, value ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .border(BorderStroke(0.8.dp, palette.border), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = palette.subText, modifier = Modifier.weight(1f))
                QButton("删除", palette, Modifier.width(64.dp), compact = true, primary = false, dangerText = true) {
                    onValues(values.filterIndexed { i, _ -> i != index })
                }
            }
            fields(value) { updated -> onValues(values.mapIndexed { i, old -> if (i == index) updated else old }) }
        }
    }
    QButton("添加$title", palette, primary = false, compact = true, icon = Icons.Rounded.Add) { onValues(values + newItem()) }
}

@Composable
private fun ProxyNetworksEditor(cidrs: List<String>, mappings: List<ProxyMapping>, palette: Palette, onValues: (List<String>, List<ProxyMapping>) -> Unit) {
    val entries = cidrs.map { cidr -> mappings.firstOrNull { it.cidr == cidr } ?: ProxyMapping(cidr) }
    ConfigRulesEditor("子网代理", entries, { ProxyMapping() }, palette, { updated ->
        onValues(updated.map { it.cidr }, updated.filter { it.mappedCidr.isNotBlank() || it.allow != null })
    }) { rule, edit ->
        FieldBlock("代理网段", palette) { QTextField(rule.cidr, "192.168.1.0/24", palette) { edit(rule.copy(cidr = it)) } }
        FieldBlock("映射网段", palette) { QTextField(rule.mappedCidr, "可选，例如 10.168.1.0/24", palette) { edit(rule.copy(mappedCidr = it)) } }
        SwitchRow("限制访问来源组", rule.allow != null, palette) { edit(rule.copy(allow = if (it) emptyList() else null)) }
        rule.allow?.let { groups ->
            ListEditor("允许访问的组", groups, "组名；空列表拒绝所有组", palette) { edit(rule.copy(allow = it)) }
        }
    }
}

@Composable
private fun PortForwardsEditor(values: List<PortForwardRule>, palette: Palette, onValues: (List<PortForwardRule>) -> Unit) {
    ConfigRulesEditor("端口转发", values, { PortForwardRule() }, palette, onValues) { rule, edit ->
        FieldBlock("协议", palette) { QSelect(rule.proto, listOf("tcp", "udp"), palette) { edit(rule.copy(proto = it)) } }
        FieldBlock("监听地址", palette) { QTextField(rule.bindAddr, "127.0.0.1:8080", palette) { edit(rule.copy(bindAddr = it)) } }
        FieldBlock("目标地址", palette) { QTextField(rule.dstAddr, "10.126.126.2:80", palette) { edit(rule.copy(dstAddr = it)) } }
    }
}

@Composable
private fun VpnPortalClientsEditor(values: List<VpnPortalClient>, palette: Palette, onValues: (List<VpnPortalClient>) -> Unit) {
    ConfigRulesEditor("接入客户端", values, { VpnPortalClient() }, palette, onValues) { client, edit ->
        FieldBlock("名称", palette) { QTextField(client.name, "客户端名称", palette) { edit(client.copy(name = it)) } }
        FieldBlock("虚拟地址", palette) { QTextField(client.virtualIp, "10.126.126.10/24", palette) { edit(client.copy(virtualIp = it)) } }
        ListEditor("所属组", client.groups, "组名", palette) { edit(client.copy(groups = it)) }
    }
}

@Composable
private fun ManagedCredentialsEditor(values: List<ManagedCredential>, palette: Palette, onValues: (List<ManagedCredential>) -> Unit) {
    ConfigRulesEditor("受管凭据", values, { ManagedCredential() }, palette, onValues) { rule, edit ->
        FieldBlock("凭据 ID", palette) { QTextField(rule.id, "凭据标识", palette) { edit(rule.copy(id = it)) } }
        FieldBlock("凭据密钥", palette) { QTextField(rule.secret, "Base64 私钥", palette, password = true) { edit(rule.copy(secret = it)) } }
        ListEditor("所属组", rule.groups, "组名", palette) { edit(rule.copy(groups = it)) }
        SwitchRow("允许中继", rule.allowRelay, palette) { edit(rule.copy(allowRelay = it)) }
        SwitchRow("可重复使用", rule.reusable, palette) { edit(rule.copy(reusable = it)) }
        FieldBlock("过期时间", palette) { QTextField(rule.expiryUnix, "Unix 秒，0 表示不过期", palette) { edit(rule.copy(expiryUnix = it)) } }
        ListEditor("允许代理网段", rule.allowedProxyCidrs, "192.168.1.0/24", palette) { edit(rule.copy(allowedProxyCidrs = it)) }
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
    QCard(palette) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
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
private fun ExpandArrow(
    expanded: Boolean,
    palette: Palette,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "expand-arrow"
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            null,
            tint = palette.text,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = arrowRotation }
        )
    }
}

@Composable
private fun ActionBar(palette: Palette, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(0.8.dp, palette.border), RoundedCornerShape(22.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    PopupSelect(
        value = value.ifBlank { options.firstOrNull().orEmpty() },
        options = (options + value.takeIf { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.distinct().map { PopupSelectOption(value = it, label = it) },
        palette = palette,
        height = 44.dp,
        onValue = onValue
    )
}

private data class PopupSelectOption(
    val value: String,
    val label: String,
    val subtitle: String = "",
    val running: Boolean = false
)

@Composable
private fun PopupSelect(
    value: String,
    options: List<PopupSelectOption>,
    palette: Palette,
    height: Dp = 46.dp,
    onValue: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == value } ?: options.firstOrNull()
    val arrowRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(180),
        label = "select-arrow"
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surfaceVariant)
                .border(BorderStroke(0.8.dp, if (open) palette.text.copy(alpha = 0.32f) else palette.border), RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.label ?: "暂无配置",
                    color = palette.text,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selected?.running == true) {
                    Text("运行中", color = palette.success, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    null,
                    tint = palette.subText,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(maxWidth).heightIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = palette.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(0.8.dp, palette.border)
        ) {
            options.forEach { option ->
                val checked = option.value == value
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                Row(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(when {
                            pressed -> palette.secondaryButton
                            checked -> palette.surfaceVariant
                            else -> Color.Transparent
                        })
                        .selectable(checked, source, indication = null, role = Role.RadioButton) {
                            onValue(option.value)
                            open = false
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(option.label, color = palette.text, fontSize = 14.sp,
                            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (option.subtitle.isNotBlank()) {
                            Text(option.subtitle, color = palette.subText, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (option.running) {
                        Spacer(Modifier.width(8.dp))
                        Text("运行中", color = palette.success, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.Check, if (checked) "已选择" else null,
                        tint = if (checked) palette.text else Color.Transparent, modifier = Modifier.size(18.dp))
                }
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
private fun AppleToast(
    title: String,
    body: String,
    palette: Palette,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(2800)
        visible = false
        delay(320)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(320, delayMillis = 0)
            ) + fadeIn(tween(240)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(260)
            ) + fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 56.dp)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.surface.copy(alpha = 0.97f))
                    .border(BorderStroke(0.5.dp, palette.border), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { visible = false }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (body.isNotBlank()) {
                        Text(body, color = palette.subText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QCard(palette: Palette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(14.dp))
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
    iconRotation: Float = 0f,
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
            .then(if (dangerText) Modifier.border(BorderStroke(0.8.dp, if (enabled) palette.error else palette.border.copy(alpha = 0.35f)), RoundedCornerShape(16.dp)) else Modifier)
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
                Icon(
                    icon,
                    null,
                    tint = if (enabled) fg else palette.subText,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = iconRotation }
                )
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
            .heightIn(min = if (singleLine) 44.dp else 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(if (focused) 1.2.dp else 0.7.dp, if (focused) palette.accent.copy(alpha = 0.82f) else palette.border), RoundedCornerShape(14.dp))
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
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 0.dp, tween(180), label = "switch-thumb")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(130), label = "switch-scale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (checked) palette.accent else palette.secondaryButton)
                .border(BorderStroke(if (checked) 0.dp else 0.7.dp, palette.border), CircleShape)
                .clickable(source, indication = null) { onChecked(!checked) },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
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
                                    .clip(RoundedCornerShape(13.dp))
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
        shape = RoundedCornerShape(22.dp)
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
        FormLabel(title, palette)
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
private fun FormLabel(text: String, palette: Palette) {
    Text(text, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, old -> if (i == index) value else old }

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
